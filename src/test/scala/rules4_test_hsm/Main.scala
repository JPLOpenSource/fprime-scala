package rules4_test_hsm

/**
 * Rule-based testing of the imaging system described in the paper:
 * "Modeling and Monitoring of Hierarchical State Machines in Scala",
 * Klaus Havelund and Rajeev Joshi,
 * 9th International Workshop on Software Engineering for Resilient Systems (SERENE 2017),
 * September 4-5, 2017, Geneva, Switzerland. Lecture Notes in Computer Science Volume 10479.
 *
 * The imaging system is programmed as an F' component, and the rule-based tester as
 * another component.
 */

import fprime._
import hsm._
import daut._
import rules._

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
      missedEvents ++= List(event)
    }

    def submit(): Unit = {
      missedEvents match {
        case Nil =>
        case event :: rest =>
          missedEvents = rest
          println(s"resubmiting $event, resulting in new queue: $this")
          selfTrigger(event)
      }
    }

    override def toString: String = missedEvents.mkString("[",",","]")
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
    case input =>
      if (!Machine(input)) {
        MissedEvents.add(input)
        println(s"$input stored as missed: $MissedEvents")
      }
  }
}

class Test extends Component {
  val i_tck = new Input[Unit]
  val i_obs = new ObsInput
  val i_cam = new Input[Imaging2Camera]
  val o_cmd = new CommandOutput
  val o_cam = new Output[Camera2Imaging]

  object TestRules extends Rules {
    val MAX_IMAGES: Int = 1000
    val MAX_SHUTDOWNS: Int = 1000
    val MAX_READY: Int = 1000
    var imageCount: Int = 0
    var shutdownCount: Int = 0
    var readyCount: Int = 0

    rule("TakeImage") {
      imageCount < MAX_IMAGES
    } -> {
      o_cmd.invoke((TakeImage(imageCount)))
      imageCount += 1
    }
    rule("ShutDown") {
      shutdownCount < MAX_SHUTDOWNS
    } -> {
      o_cmd.invoke(ShutDown)
      shutdownCount += 1
    }
    rule("Ready") {
      readyCount < MAX_READY
    } -> {
      o_cam.invoke(Ready)
      readyCount += 1
    }

    //strategy(Random())
    strategy(Pick())
  }

  object SaveOrAbort extends Monitor[Observation] {
    always {
      case EvrTakeImage(_) => hot {
        case EvrImageSaved | EvrImageAborted => ok
        case EvrTakeImage(_) => error("Image was not saved or aborted")
      }
    }
  }

  override def when: PartialFunction[Any, Unit] = {
    case _: Unit => TestRules.fire()
    case o: Observation => SaveOrAbort.verify(o)
    case _ =>
  }
}

// Main:

object Main {
  def main(args: Array[String]): Unit = {
    FPrimeOptions.DEBUG = true
    HSMOptions.DEBUG = true
    RulesOptions.DEBUG = true
    DautOptions.DEBUG = false

    val imaging = new Imaging
    val test = new Test

    test.o_cmd.connect(imaging.i_cmd)
    test.o_cam.connect(imaging.i_cam)
    imaging.o_cam.connect(test.i_cam)
    imaging.o_obs.connect(test.i_obs)

    println("Begin!")

    Configuration.show("/Users/khavelun/Desktop/config.dot")

    repeat(1000) {
      Thread.sleep(100)
      println("=" * 80)
      test.i_tck.invoke(())
    }

    println("End!")
  }
}
