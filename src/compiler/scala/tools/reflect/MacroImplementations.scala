package scala.tools.reflect

import scala.reflect.macros.contexts.Context
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Stack
import scala.reflect.internal.util.OffsetPosition

abstract class MacroImplementations {
  val c: Context

  import c.universe._
  import definitions._

  def macro_StringInterpolation_f(parts: List[Tree], args: List[Tree], origApplyPos: c.universe.Position): Tree = {
    // the parts all have the same position information (as the expression is generated by the compiler)
    // the args have correct position information

    // the following conditions can only be violated if invoked directly
    if (parts.length != args.length + 1) {
      if(parts.length == 0)
        c.abort(c.prefix.tree.pos, "too few parts")
      else if(args.length + 1 < parts.length)
        c.abort(if(args.length==0) c.enclosingPosition else args.last.pos,
            "too few arguments for interpolated string")
      else
        c.abort(args(parts.length-1).pos,
            "too many arguments for interpolated string")
    }

    val pi = parts.iterator
    val bldr = new java.lang.StringBuilder
    val evals = ListBuffer[ValDef]()
    val ids = ListBuffer[Ident]()
    val argStack = Stack(args : _*)

    def defval(value: Tree, tpe: Type): Unit = {
      val freshName = newTermName(c.freshName("arg$"))
      evals += ValDef(Modifiers(), freshName, TypeTree(tpe) setPos value.pos.focus, value) setPos value.pos
      ids += Ident(freshName)
    }

    def isFlag(ch: Char): Boolean = {
      ch match {
        case '-' | '#' | '+' | ' ' | '0' | ',' | '(' => true
        case _ => false
      }
    }

    def checkType(arg: Tree, variants: Type*): Option[Type] = {
      variants.find(arg.tpe <:< _).orElse(
        variants.find(c.inferImplicitView(arg, arg.tpe, _) != EmptyTree).orElse(
            Some(variants(0))
        )
      )
    }

    val stdContextTags = new { val tc: c.type = c } with StdContextTags
    import stdContextTags._

    def conversionType(ch: Char, arg: Tree): Option[Type] = {
      ch match {
        case 'b' | 'B' =>
          if(arg.tpe <:< NullTpe) Some(NullTpe) else Some(BooleanTpe)
        case 'h' | 'H' =>
          Some(AnyTpe)
        case 's' | 'S' =>
          Some(AnyTpe)
        case 'c' | 'C' =>
          checkType(arg, CharTpe, ByteTpe, ShortTpe, IntTpe)
        case 'd' | 'o' | 'x' | 'X' =>
          checkType(arg, IntTpe, LongTpe, ByteTpe, ShortTpe, tagOfBigInt.tpe)
        case 'e' | 'E' | 'g' | 'G' | 'f' | 'a' | 'A'  =>
          checkType(arg, DoubleTpe, FloatTpe, tagOfBigDecimal.tpe)
        case 't' | 'T' =>
          checkType(arg, LongTpe, tagOfCalendar.tpe, tagOfDate.tpe)
        case _ => None
      }
    }

    def copyString(first: Boolean): Unit = {
      val strTree = pi.next()
      val rawStr = strTree match {
        case Literal(Constant(str: String)) => str
        case _ => throw new IllegalArgumentException("internal error: argument parts must be a list of string literals")
      }
      val str = StringContext.treatEscapes(rawStr)
      val strLen = str.length
      val strIsEmpty = strLen == 0
      def charAtIndexIs(idx: Int, ch: Char) = idx < strLen && str(idx) == ch
      def isPercent(idx: Int) = charAtIndexIs(idx, '%')
      def isConversion(idx: Int) = isPercent(idx) && !charAtIndexIs(idx + 1, 'n') && !charAtIndexIs(idx + 1, '%')
      var idx = 0

      def errorAtIndex(idx: Int, msg: String) = c.error(new OffsetPosition(strTree.pos.source, strTree.pos.point + idx), msg)
      def wrongConversionString(idx: Int) = errorAtIndex(idx, "wrong conversion string")
      def illegalConversionCharacter(idx: Int) = errorAtIndex(idx, "illegal conversion character")
      def nonEscapedPercent(idx: Int) = errorAtIndex(idx,
        "conversions must follow a splice; use %% for literal %, %n for newline")

      // STEP 1: handle argument conversion
      // 1) "...${smth}" => okay, equivalent to "...${smth}%s"
      // 2) "...${smth}blahblah" => okay, equivalent to "...${smth}%sblahblah"
      // 3) "...${smth}%" => error
      // 4) "...${smth}%n" => okay, equivalent to "...${smth}%s%n"
      // 5) "...${smth}%%" => okay, equivalent to "...${smth}%s%%"
      // 6) "...${smth}[%legalJavaConversion]" => okay, according to http://docs.oracle.com/javase/1.5.0/docs/api/java/util/Formatter.html
      // 7) "...${smth}[%illegalJavaConversion]" => error
      if (!first) {
        val arg = argStack.pop()
        if (isConversion(0)) {
          // PRE str is not empty and str(0) == '%'
          // argument index parameter is not allowed, thus parse
          //    [flags][width][.precision]conversion
          var pos = 1
          while (pos < strLen && isFlag(str charAt pos)) pos += 1
          while (pos < strLen && Character.isDigit(str charAt pos)) pos += 1
          if (pos < strLen && str.charAt(pos) == '.') {
            pos += 1
            while (pos < strLen && Character.isDigit(str charAt pos)) pos += 1
          }
          if (pos < strLen) {
            conversionType(str charAt pos, arg) match {
              case Some(tpe) => defval(arg, tpe)
              case None => illegalConversionCharacter(pos)
            }
          } else {
            wrongConversionString(pos - 1)
          }
          idx = 1
        } else {
          bldr append "%s"
          defval(arg, AnyTpe)
        }
      }

      // STEP 2: handle the rest of the text
      // 1) %n tokens are left as is
      // 2) %% tokens are left as is
      // 3) other usages of percents are reported as errors
      if (!strIsEmpty) {
        while (idx < strLen) {
          if (isPercent(idx)) {
            if (isConversion(idx)) nonEscapedPercent(idx)
            else idx += 1 // skip n and % in %n and %%
          }
          idx += 1
        }
        bldr append (str take idx)
      }
    }

    copyString(first = true)
    while (pi.hasNext) {
      copyString(first = false)
    }

    val fstring = bldr.toString
//  val expr = c.reify(fstring.format((ids.map(id => Expr(id).eval)) : _*))
//  https://issues.scala-lang.org/browse/SI-5824, therefore
    val expr =
      Apply(
        Select(
          Literal(Constant(fstring)),
          newTermName("format")),
        List(ids: _* )
      )

    Block(evals.toList, atPos(origApplyPos.focus)(expr)) setPos origApplyPos.makeTransparent
  }

}
