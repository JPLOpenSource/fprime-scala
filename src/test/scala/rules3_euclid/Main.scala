package rules3_euclid

import rules._

/*

Euclid's algorithm form finding greatest common divisor, as taken from
https://en.wikipedia.org/wiki/Guarded_Command_Language:

a, b := A, B;
do
   a < b → b := b - a
 | b < a → a := a - b
od
 */

class Euclid(A: Int, B: Int) extends Rules {
  var a : Int = A
  var b : Int = B

  invariant { a > 0 && b > 0 }

  rule ("r1") (a < b) -> {b = b - a} post (b > 0)
  rule ("r2") (b < a) -> {a = a - b} post (a > 0)
}

object Main1 {
  def main(args: Array[String]): Unit = {
    val rules = new Euclid(28,16)
    rules.fire()
    println(s"a = ${rules.a}, b = ${rules.b}")
  }
}

object Main2 {
 def main(args: Array[String]): Unit = {
   val rules = new Euclid(28,16) {
     override def afterRule() {println(s"a = $a, b = $b")}
   }
   rules.fire()
 }
}
