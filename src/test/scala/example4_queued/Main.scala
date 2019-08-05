package example4_queued

/**
 * Testing QueuedComponent.
 */

import fprime._

class A extends Component {
  val int_in = new Input[Int]
  val int_out = new Output[Int]

  def when = {
    case x: Int =>
      println(s"A inserts $x")
      int_out.invoke(x)
  }
}

class Q extends QueuedComponent[Int] {
  val int_in = new QueuedInput[Int]()
  val int_get = new SyncInput[Unit, Option[Int]] {
    override def invoke(x: Unit): Option[Int] = {
      get()
    }
  }
}

class B extends Component {
  val unit_in = new Input[Unit]()
  val int_out = new SyncOutput[Unit, Option[Int]]

  def when = {
    case _ =>
      var done = false
      while (!done) {
        int_out.invoke() match {
          case None => done = true
          case Some(x) => println(s"B gets $x")
        }
      }
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val a = new A
    val b = new B
    val q = new Q

    a.int_out.connect(q.int_in)
    b.int_out.connect(q.int_get)

    a.int_in.invoke(1)
    a.int_in.invoke(2)
    a.int_in.invoke(3)
    Thread.sleep(1)
    b.unit_in.invoke()
  }
}
