package rules1_basics

import rules._

class Rules extends RuleDSL {
  var x: Int = 0

  val r1 = rule("r1") {
    x == 0
  } -> {
    x = 100
    println(s"x = $x")
  }
  val r2 = rule("r2") {
    x == 0
  } -> {
    x = -100
    println(s"x = $x")
  }
  val r3 = rule("r3") {
    x > 0
  } -> {
    x -= 1
    println(s"x = $x")
  } post {
    x < 100
  }
  val r4 = rule("r4") {
    x < 0
  } -> {
    x += 1
    println(s"x = $x")
  } post {
    x > -100
  }
}

class Strategy1 extends Rules {
  strategy(All(r1, r3, r3, r2, r3))
}

class Strategy2 extends Rules {
  strategy(Enabled(r1, r3, r3, r2, r3))
}

class Strategy3 extends Rules {
  strategy(First(r3, r3, r2, r3))
}

class Strategy4 extends Rules {
  strategy(Until(r1, r3, r3, r2, r3))
}

class Strategy5 extends Rules {
  strategy(While(x == 0 || x > 80, Enabled(r1, r2, r3, r4)))
}

class Strategy6 extends Rules {
  strategy(
    Seq(
      Enabled(r1, r2),
      If(x > 0,
        While(x > 80, Enabled(r1, r2, r3, r4)),
        While(x < -80, Enabled(r1, r2, r3, r4))
      )))
}

class Strategy7 extends Rules {
  strategy(Random)
}

class Strategy8 extends Rules {
  strategy(Random(100))
}

class Strategy9 extends Rules {
  strategy(Bounded(10, Random(10, r1,r3)))
}

object Main {
  def main(args: Array[String]): Unit = {
    val rules = new Strategy9
    rules.fire()
  }
}


