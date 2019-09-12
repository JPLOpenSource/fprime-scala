package rules2_countdown

import rules._

class CountDown extends Rules {
  var x : Int = 11

  rule ("count down") {x > 0} -> {
    x = x - 1
    println(x)
  }

  strategy(Random)
}

object Main {
  def main(args: Array[String]) {
    val count = new CountDown
    count.fire()
  }
}
