package rules5_strategies

import rules._

class RuleSystem extends Rules {
  var x: Int = 0
  var y: Int = 0
  var z: Int = 0

  val xUp = rule("x up")(x < 5) -> {
    x += 1
  }
  val yUp = rule("y up")(y < 5) -> {
    y += 1
  }
  val zUp = rule("z up")(z < 5) -> {
    z += 1
  }

  val xDown = rule("x down")(x > 0) -> {
    x -= 1
  }
  val yDown = rule("y down")(y > 0) -> {
    y -= 1
  }
  val zDown = rule("z down")(z > 0) -> {
    z -= 1
  }

  override def afterRule(): Unit = {
    println(s"x = $x, y = $y, z = $z")
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val rules = new RuleSystem {
      strategy(
        Seq(
          Random(xUp, yUp),
          While(z < 3, zUp),
          All(xDown,yDown,zDown),
          Random(3)
        )
      )
    }
    rules.fire()
  }
}
