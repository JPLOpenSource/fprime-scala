package example9_portarrays

/**
 * Port arrays.
 */

import akka.actor.Actor.Receive
import fprime._

class Sender extends Component {
  val int_in = new Input[Int]
  val int_out = new ArrayOutput[Int](3)

  override def when: Receive = {
    case x:Int =>
      val index = x % 3
      int_out.invoke(index, x)
  }
}

class Receiver extends Component {
  val int_in = new Input[Int]

  override def when: Receive = {
    case x => println(s"$componentName received $x")
  }
}

class A extends Receiver
class B extends Receiver
class C extends Receiver

class Monitor extends Component {
  val int_in = new Input[Int]

  override def when: Receive = {
    case x : Int => assert(0 <= x && x <= 2)
  }
}

class Ground extends Component {
  val int_in = new Input[Int]
  val int_out = new Output[Int]

  override def when: Receive = {
    case x : Int => int_out.invoke(x)
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val sender = new Sender
    val a = new A
    val b = new B
    val c = new C
    val monitor = new Monitor
    val ground = new Ground

    sender.int_out.connect(
      (0 -> a.int_in),
      (1 -> b.int_in),
      (2 -> c.int_in))

    sender.int_out.connectListeners(monitor.int_in)

    ground.int_out.connect(sender.int_in)

    for (i <- 0 to 100) {
      ground.int_in.invoke(i)
    }
  }
}

