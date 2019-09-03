package fprime12_timers

import akka.actor._
import scala.concurrent.duration._
import scala.language.postfixOps

class MyActor extends Actor {
  context.setReceiveTimeout(30 milliseconds)

  def receive = {
    case "Hello" =>
      context.setReceiveTimeout(100 milliseconds)
    case ReceiveTimeout =>
      context.setReceiveTimeout(Duration.Undefined)
  }
}
