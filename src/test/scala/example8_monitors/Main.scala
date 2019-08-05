package example8_monitors

/**
 * Monitors
 */

import akka.actor.Actor.Receive
import fprime._

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

  var seen : Set[Int] = Set()

  override def when: Receive = {
    case x : Int =>
      println(s"M receives $x")
      if (seen.contains(x)) {
        obs_out.invoke(M.Error(x.toString))
      }
      seen += x
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

/*






trait EVR

case class EnterState(task: RaHSM, state: String) extends EVR

case class ExitState(task: RaHSM, state: String) extends EVR

case class Message(sender: RaHSM, event: EventId, receiver: RaHSM) extends EVR

case class Action(name: Any*) extends EVR

class AgentMonitor extends Monitor[EVR] {
  val violatedTasks = Global.locktable.violatedTasks _

  override def callBack(): Unit = {
    HSM.printTrace()
    println(s"\nOn iteration: ${Scheduler.iteration}\n")
  }

  stopOnError()
}

class PropertyMonitor extends AgentMonitor {
  monitor(
    //new ReleaseMonitor//,
    new AbortMonitor
  )
}

class ReleaseMonitor extends AgentMonitor {
  always {
    case EnterState(task, state) if state == "TERMINATED" || state == "ABORTED" =>
      task.asInstanceOf[Task].hasReleasedProperty()
  }
}

class AbortMonitor extends AgentMonitor {
  var abortedTasks: Set[RaHSM] = Set()

  always {
    case Message(_, ABORT, target) =>
      abortedTasks += target
    case Action(MEMORY_EVENT) | Action(SNARF_EVENT) =>
      for (task <- violatedTasks() if !abortedTasks.contains(task)) yield hot {
        case Message(_, ABORT, target) if target.hsmName == task.hsmName => ok
      }
  }
}

 */