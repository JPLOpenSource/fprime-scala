package example6_cmd_telem

/**
 * Testing commands, parameters, events, and telemetry.
 */

import fprime._

/**********
 * Commands
 **********/

case class IncreaseIntWith(x: Int) extends Command

case class DecreaseIntWith(x: Int) extends Command

case class IncreaseFloatWith(f: Float) extends Command

case class DecreaseFloatWith(f: Float) extends Command

/********
 * Events
 ********/

case class IntChanged(component: String, value: Int) extends Event
case class FloatChanged(component: String, value: Float) extends Event

/************
 * Components
 ************/

class A extends Component {
  val commands_in = new CommandInput
  val events_out = new ObsOutput
  val int_out = new Output[Int]

  var i: Int = 0
  var f: Float = 0

  override def processCommand(cmd: Command): Unit = {
    cmd match {
      case IncreaseIntWith(x) =>
        i += x
        events_out.invoke(IntChanged("A", i))
        int_out.invoke(i)
      case DecreaseIntWith(x) =>
        i -= x
        events_out.invoke(IntChanged("A", i))
      case IncreaseFloatWith(g) =>
        f += g
        events_out.invoke(Telemetry(Map("A.f" -> f)))
      case DecreaseFloatWith(g) =>
        f -= g
        events_out.invoke(Telemetry(Map("A.f" -> f)))
    }
  }

  def when = {
    case cmd: Command =>
      processCommand(cmd)
    case _ => ()
  }
}

class B extends Component {
  val int_in = new Input[Int]
  val float_in = new Input[Float]

  val events_out = new ObsOutput

  var i: Int = 0
  var f: Float = 0

  def when = {
    case x: Int =>
      i = x
      events_out.invoke(IntChanged("B", i))
    case g: Float =>
      f = g
      events_out.invoke(Telemetry(Map("B.f" -> f)))
  }
}

class C extends Component {
  val commands_in = new CommandInput

  val events_out = new ObsOutput
  val float_out = new Output[Float]

  var i: Int = 0
  var f: Float = 0

  override def processCommand(cmd: Command): Unit = {
    cmd match {
      case IncreaseIntWith(x) =>
        i += x
        events_out.invoke(IntChanged("C", i))
      case DecreaseIntWith(x) =>
        i -= x
        events_out.invoke(IntChanged("C", i))
      case IncreaseFloatWith(g) =>
        f += g
        events_out.invoke(Telemetry(Map("C.f" -> f)))
        float_out.invoke(f)
      case DecreaseFloatWith(g) =>
        f -= g
        events_out.invoke(Telemetry(Map("C.f" -> f)))
    }
  }

  def when = {
    case cmd: Command =>
      processCommand(cmd)
    case _ => ()
  }
}

/********
 * Ground
 ********/

class Ground extends Component {
  val goal_int_in = new Input[Int]
  val goal_float_in = new Input[Float]
  val events_in = new ObsInput

  val commands_out_A = new CommandOutput
  val commands_out_C = new CommandOutput

  def when = {
    case x : Int =>
      if (x >= 0) {
        commands_out_A.invoke(IncreaseIntWith(x))
        commands_out_C.invoke(IncreaseIntWith(x))
      } else {
        commands_out_A.invoke(DecreaseIntWith(-x))
        commands_out_C.invoke(DecreaseIntWith(-x))
      }
    case g : Float =>
      if (g >= 0) {
        commands_out_A.invoke(IncreaseFloatWith(g))
        commands_out_C.invoke(IncreaseFloatWith(g))
      } else {
        commands_out_A.invoke(DecreaseFloatWith(-g))
        commands_out_C.invoke(DecreaseFloatWith(-g))
      }
    case e : Event =>
      println(e)
    case t : Telemetry =>
      println(t)
  }
}

/******
 * Main
 ******/

object Main {
  def main(args: Array[String]): Unit = {
    val ground = new Ground
    val a = new A
    val b = new B
    val c = new C

    ground.commands_out_A.connect(a.commands_in)
    ground.commands_out_C.connect(c.commands_in)

    a.events_out.connect(ground.events_in)
    a.int_out.connect(b.int_in)
    b.events_out.connect(ground.events_in)
    c.events_out.connect(ground.events_in)
    c.float_out.connect(b.float_in)

    for (k <- 0 to 100) {
      ground.goal_int_in.invoke(k)
      ground.goal_float_in.invoke(k * 1.5f)
    }
  }
}