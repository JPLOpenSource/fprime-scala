
/**
 * Rules is an internal Scala DSL for writing rule-based tests. The DSL, however, can be seen as a stand-alone
 * DSL for writing rule-based programs in the style of guarded commands. A rule program is a set of rules.
 * A rule consists of a condition, a pre-condition, an action, and an optional predicate, the post-condition,
 * to be verified (if present) after the action has been executed. A rule can e.g be declared as follows:
 *
 * {{{
 * rule("r3") { x > 0 } -> { x -= 1 } post { x < 100 }
 * }}}
 *
 * which reads: if `x > 0` then subtract `1` from `x` and verify that `x < 100`.
 * If the post-condition fails, an error will be recorded. The rule has the name `r3`.
 *
 * Note that references to rules can be used in defining a rule execution strategy,
 * so sometimes it is useful to assign these references to variables, as in:
 *
 * {{{
 * val r3 = rule("r3") { x > 0 } -> { x -= 1 } post { x < 100 }
 * }}}
 *
 * To illustrate an example, consider Euclid's algorithm for computing the greatest
 * common divisor of two numbers `A` and `B`, as presented in
 * [[https://en.wikipedia.org/wiki/Guarded_Command_Language Guarded Command Language]]:
 *
 * {{{
 * a, b := A, B;
 * do
 *     a < b → b := b - a
 *   | b < a → a := a - b
 * od
 * }}}
 *
 * This algorithm can be encoded in Rules as follows.
 *
 * {{{
 * class Euclid(A: Int, B: Int) extends Rules {
 *   var a : Int = A
 *   var b : Int = B
 *
 *   rule ("r1") { a < b} -> {b = b - a}
 *   rule ("r2") { b < a} -> {a = a - b}
 *
 *   override def after() {println(s"a = $a, b = $b")}
 *   strategy(Random)
 * }
 * }}}
 *
 * The class `Euclid` (parameterized with the numbers `A` and `B`) subclasses the class
 * `Rules`, which defines the rule DSL. Two variables are declared: `a` and `b`, and two rules
 * are declared.
 *
 * In addition the function `after` is overridden. It is automatically called after each rule execution,
 * and in this case will print the values of the variables `a` and `b`.
 *
 * Finally we have to indicate according to which strategy the rules are executed. In this case
 * we choose: random execution until no rules are enabled (pre-conditions evaluate to true).
 *
 * Let's now try to apply our rule program. This is done in the following main program:
 *
 *{{{
 * object Main {
 *  def main(args: Array[String]): Unit = {
 *    val rules = new Euclid(212,34)
 *    rules.fire()
 *  }
 * }
 * }}}
 */

package object rules {}
