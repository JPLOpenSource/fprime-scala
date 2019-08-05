package example10_temporal_monitors

/**
 * Monitors, the same example as example8, but this time using a Daut monitor.
 */

import akka.actor.Actor.Receive
import fprime._
import daut.Monitor

class A extends Component {
  val int_in = new Input[Int]
  val int_out = new Output[Int]

  override def when: Receive = {
    case x:Int =>
      //println(s"A sending $x")
      int_out.invoke(x)
  }
}

class B extends Component {
  val int_in = new Input[Int]

  override def when: Receive = {
    case x => //println(s"B received $x")
  }
}

class M extends Component {
  val int_in = new Input[Int]
  val obs_out = new ObsOutput

  object UniqueNumbers extends Monitor[Int] {
    always {
      case x => watch {
        case `x` => error(s"$x occurs more than once")
      }
    }
  }

  override def when: Receive = {
    case x : Int =>
      println(s"M receives $x")
      UniqueNumbers.verify(x)
  }
}

object M {
  case class Error(msg: String) extends Event
}

class Ground extends Component {
  val int_in = new Input[Int]
  val obs_in = new ObsInput()
  val int_out = new Output[Int]

  override def when: Receive = {
    case x : Int => int_out.invoke(x)
    case e : Event => println(s"*** Ground receives event: $e")
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val a = new A
    val b = new B
    val m = new M
    val ground = new Ground
    a.int_out.connect(b.int_in, m.int_in)
    m.obs_out.connect(ground.obs_in)
    ground.int_out.connect(a.int_in)
    for (i <- 1 to 100) {
      ground.int_in.invoke(i)
    }
    ground.int_in.invoke(4)
  }
}
