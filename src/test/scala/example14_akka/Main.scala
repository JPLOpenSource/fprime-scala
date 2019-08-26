package example14_akka

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}

case class A(n: Int)

case class B(n: Int)

case class C(n: Int)

case class D(n: Int)

case class E(n: Int)

class TheActor extends Actor {
  var K: Int = 0

  override def receive: Receive = {
    case A(x) =>
      println(s"received A($x), start delay")
      Thread.sleep(5)
      println(s"end delay")
    case B(y) if y == K =>
      println(s"received B($y)")
    case C(z) =>
      println(s"received C($z)")
      K = 2
    case D(v) =>
      println(s"received D($v)")
  }

  override def unhandled(message: Any): Unit = {
    println(s"+++++ THIS MESSAGE GOT LOST: $message")
    self forward message
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    val system: ActorSystem = ActorSystem("Experiment")
    val a: ActorRef = system.actorOf(Props(new TheActor))

    a ! A(1)
    a ! B(2)
    a ! C(3)
    a ! D(4)
  }
}
