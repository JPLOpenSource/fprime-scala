package fprime7_simple

import scala.language.postfixOps

/**
 * Just a simple example
 */

import fprime._

/** ********
 * Commands
 * *********/

case class SetValue(x: Float) extends Command

/** **********
 * Components
 * ***********/

class A extends Component {
  val commands_in = new CommandInput
  val float_out = new Output[Float]
  val obs_out = new ObsOutput

  var value: Float = 0.0f

  override def executeCommand(cmd: Command): Unit = {
    cmd match {
      case SetValue(x) =>
        value = x
        val valueToSend = getParameter("toAdd") match {
          case None => value
          case Some(delta) => value + delta
        }
        float_out.invoke(valueToSend)
        obs_out.logTelem("value" -> value)
    }
  }

  def when = {case _ => ()}
}

class B extends Component {
  val float_in = new Input[Float]
  val obs_out = new ObsOutput

  var value: Float = 0.0f

  def when = {
    case x : Float =>
      value = x
      obs_out.logTelem(("value" -> value))
  }
}

/** ******
 * Ground
 * *******/

class Ground extends Component {
  val int_in = new Input[Int]
  val float_in = new Input[Float]
  val obs_in = new ObsInput
  val commands_out = new CommandOutput

  def when = {
    case x: Int => commands_out.invoke(Parameters(Map("toAdd" -> x)))
    case x: Float => commands_out.invoke(SetValue(x))
    case t: Telemetry => println(t)
  }
}

/** ****
 * Main
 * *****/

object Main {
  def main(args: Array[String]): Unit = {
    val ground = new Ground
    val a = new A
    val b = new B

    ground.commands_out.connect(a.commands_in)
    a.float_out.connect(b.float_in)
    a.obs_out.connect(ground.obs_in)
    b.obs_out.connect(ground.obs_in)

    ground.int_in.invoke(10)
    for (x <- 1 to 100) ground.float_in.invoke(x)
  }
}