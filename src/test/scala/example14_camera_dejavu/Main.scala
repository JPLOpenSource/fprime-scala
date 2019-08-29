package example14_camera_dejavu

/**
 * The imaging system described in the paper:
 * "Modeling and Monitoring of Hierarchical State Machines in Scala",
 * Klaus Havelund and Rajeev Joshi,
 * 9th International Workshop on Software Engineering for Resilient Systems (SERENE 2017),
 * September 4-5, 2017, Geneva, Switzerland. Lecture Notes in Computer Science Volume 10479.
 *
 * The monitor in the Ground component verifies a past time temporal logic property in the
 * DejaVu monitoring logic.
 */

import fprime._
import hsm._
import qtl._

import akka.actor.ReceiveTimeout
import scala.language.postfixOps

// Messages:

case class TakeImage(d: Int) extends Command

case object ShutDown extends Command

trait Imaging2Camera

case object Open extends Imaging2Camera

case object Close extends Imaging2Camera

case object PowerOn extends Imaging2Camera

case object PowerOff extends Imaging2Camera

case object SaveData extends Imaging2Camera

trait Camera2Imaging

case object Ready extends Camera2Imaging

case class EvrTakeImage(d: Int) extends Event

case object EvrOpen extends Event

case object EvrClose extends Event

case object EvrPowerOn extends Event

case object EvrPowerOff extends Event

case object EvrSaveData extends Event

case object EvrImageSaved extends Event

case object EvrImageAborted extends Event

// Components:

class Imaging extends Component {
  val i_cmd = new CommandInput
  val i_cam = new Input[Camera2Imaging]
  val o_cam = new Output[Imaging2Camera]
  val o_obs = new ObsOutput

  object MissedEvents {
    private var missedEvents: List[Any] = Nil

    def add(event: Any): Unit = {
      // missedEvents ++= List(event)
      missedEvents ::= event
    }

    def submit(): Unit = {
      missedEvents match {
        case Nil =>
        case event :: rest =>
          missedEvents = rest
          selfTrigger(event)
      }
    }
  }

  object Machine extends HSM[Any] {
    var duration: Int = 0
    val DARK_THRESHOLD = 5

    def getTemp(): Int = 15

    states(off, on, powering, exposing, exposing_light, exposing_dark, saving)
    initial(off)

    object off extends state() {
      entry {
        MissedEvents.submit()
      }
      when {
        case TakeImage(d) => on exec {
          o_obs.logEvent(EvrTakeImage(d))
          duration = d
          o_cam.invoke(PowerOn)
        }
      }
    }

    object on extends state() {
      when {
        case ShutDown => off exec {
          o_obs.logEvent(EvrImageAborted)
          o_cam.invoke(PowerOff)
        }
      }
    }

    object powering extends state(on, true) {
      when {
        case Ready => exposing
      }
    }

    object exposing extends state(on)

    object exposing_light extends state(exposing, true) {
      entry {
        o_cam.invoke(Open)
        setTimer(duration)
      }
      exit {
        o_cam.invoke(Close)
      }
      when {
        case ReceiveTimeout => {
          if (getTemp() >= DARK_THRESHOLD) exposing_dark else saving
        }
      }
    }

    object exposing_dark extends state(exposing) {
      entry {
        setTimer(duration)
      }
      when {
        case ReceiveTimeout => saving
      }
    }

    object saving extends state(on) {
      entry {
        o_cam.invoke(SaveData)
      }
      when {
        case Ready => off exec {
          o_obs.logEvent(EvrImageSaved)
          o_cam.invoke(PowerOff)
        }
      }
    }

  }

  override def when: PartialFunction[Any, Unit] = {
    case input => if (!Machine(input)) MissedEvents.add(input)
  }
}

class Camera extends Component {
  val i_img = new Input[Imaging2Camera]
  val o_img = new Output[Camera2Imaging]
  val o_obs = new ObsOutput

  def open(): Unit = {
    o_obs.invoke(EvrOpen)
  }

  def close(): Unit = {
    o_obs.invoke(EvrClose)
  }

  def powerOn(): Unit = {
    o_obs.invoke(EvrPowerOn)
  }

  def powerOff(): Unit = {
    o_obs.invoke(EvrPowerOff)
  }

  def saveData(): Unit = {
    o_obs.invoke(EvrSaveData)
  }

  override def when: PartialFunction[Any, Unit] = {
    case Open => open()
    case Close => close()
    case PowerOn => powerOn(); o_img.invoke(Ready)
    case PowerOff => powerOff()
    case SaveData => saveData(); o_img.invoke(Ready)
  }
}

class Ground extends Component {
  val i_int = new Input[Int]
  val i_obs = new ObsInput
  val o_cmd = new CommandOutput

  // --- monitor begin ---

  /*
  prop fifo : Forall d1 . Forall d2 . (takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)
  */

  class Formula_fifo(monitor: Monitor) extends Formula(monitor) {

    val var_d1 :: var_d2 :: Nil = declareVariables(("d1", false), ("d2", false))

    override def evaluate(): Boolean = {
      now(4) = build("takeImage")(V("d2"))
      now(7) = build("requestImage")(V("d2"))
      now(10) = build("requestImage")(V("d1"))
      now(13) = build("takeImage")(V("d1"))
      now(9) = now(10).or(pre(9))
      now(8) = pre(9)
      now(6) = now(7).and(now(8))
      now(5) = now(6).or(pre(5))
      now(3) = now(4).and(now(5))
      now(12) = now(13).or(pre(12))
      now(11) = pre(12)
      now(2) = now(3).not().or(now(11))
      now(1) = now(2).forAll(var_d2.quantvar)
      now(0) = now(1).forAll(var_d1.quantvar)

      debugMonitorState()

      val error = now(0).isZero
      if (error) monitor.recordResult()
      tmp = now
      now = pre
      pre = tmp
      touchedByLastEvent = emptyTouchedSet
      !error
    }

    varsInRelations = Set()
    val indices: List[Int] = List(11, 12, 5, 8, 9)

    pre = Array.fill(14)(bddGenerator.False)
    now = Array.fill(14)(bddGenerator.False)

    txt = Array(
      "Forall d1 . Forall d2 . (takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)",
      "Forall d2 . (takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)",
      "(takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))) -> @ P takeImage(d1)",
      "takeImage(d2) & P (requestImage(d2) & @ P requestImage(d1))",
      "takeImage(d2)",
      "P (requestImage(d2) & @ P requestImage(d1))",
      "requestImage(d2) & @ P requestImage(d1)",
      "requestImage(d2)",
      "@ P requestImage(d1)",
      "P requestImage(d1)",
      "requestImage(d1)",
      "@ P takeImage(d1)",
      "P takeImage(d1)",
      "takeImage(d1)"
    )

    debugMonitorState()
  }

  /* The specialized Monitor for the provided properties. */

  class PropertyMonitor extends Monitor {
    def eventsInSpec: Set[String] = Set("takeImage", "requestImage")

    formulae ++= List(new Formula_fifo(this))
  }

  // -------------------

  val m = new PropertyMonitor
  Options.DEBUG = false
  qtl.Util.openResultFile("dejavu-results")

  // --- monitor end ---

  override def when: PartialFunction[Any, Unit] = {
    case d: Int =>
      m.submit("requestImage", d)
      o_cmd.invoke(TakeImage(d))
    case EvrTakeImage(d) =>
      m.submit("takeImage", d)
    case _ =>
  }
}

// Main:

object Main {
  def main(args: Array[String]): Unit = {
    FPrimeOptions.DEBUG = false
    HSMOptions.PRINT = false
    HSMOptions.TRACE = false

    val imaging = new Imaging
    val camera = new Camera
    val ground = new Ground

    imaging.o_cam.connect(camera.i_img)
    imaging.o_obs.connect(ground.i_obs)
    camera.o_img.connect(imaging.i_cam)
    camera.o_obs.connect(ground.i_obs)
    ground.o_cmd.connect(imaging.i_cmd)

    Configuration.show("/Users/khavelun/Desktop/config.dot")

    ground.i_int.invoke(1000)
    ground.i_int.invoke(2000)
    ground.i_int.invoke(3000)
  }
}


