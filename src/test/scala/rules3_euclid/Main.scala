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

  rule ("r1") { a < b} -> {b = b - a}
  rule ("r2") { b < a} -> {a = a - b}

  override def after() {println(s"a = $a, b = $b")}
  strategy(Random)
}

object Main {
 def main(args: Array[String]): Unit = {
   val rules = new Euclid(212,34)
   rules.fire()
 }
}
