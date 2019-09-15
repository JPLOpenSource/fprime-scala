# Testing an F' Component with Rules

I the following we will explain how the Rules DSL can be used for rule-based testing of an F' component. The entire code shown can be found in package [src/test/scala/rules4_test_hsm](src/test/scala/rules4_test_hsm). The F' component being tested is the `Imaging` state machine explained on the main page: [README.md](README.md). We shall go through the example step by step below.

Rule-based component testing is the idea of writing a non-deterministic program, which randomly invokes messages on the component, with the purpose of trying scenarios that would not otherwise have been explored in a manual test. The Rules DSL is meant for writing such non-deterministic tests. The basic idea is to define a system consisiting of two components: the `Imaging` component that we want to test, and a `Test` component, which contains a rule-based program interacting with the `Imaging` component in a randomized manner.

Note, that allthough the approach here is used for testing one component (unit testing) it can be used for testing a collection of components as well (integration testing).

## Importing our DSLs

Let's start ny declaring our package and importing all relevant DSLs. We need to import our four DSLs for writing components, state machines, monitors, and rules, and we need to import a timeout event for working with timeouts.

```scala
package rules4_test_hsm

import fprime._
import hsm._
import daut._
import rules._

import akka.actor.ReceiveTimeout
```

## Declaration of Message Types

Next we define the types of messages sent between the `Imaging` component and its environment, in our case the`Test` component.
Note that all intended communications between `Imaging` component and the `Camera` component are instead taking place with the `Test` component.

```scala
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
```

## The Imaging Component

The `Imaging` component is exactly as before (see [README.md](README.md)):

```scala
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
```

## The Test Component

Now to the interesting part, the `Test` component. The component has an input port for each output port of the `Imaging` component: `i_obs` for observations, and `i_cam` for messages the `Imaging` component normally sends to the `Camera` component. In addition, the `Test` component has an `i_tck` input port. This is used to drive the `Test` component from the `Main` program: one move at a time.

Correspondingly, the `Test` component has an output port for each input port of the `Imaging` component: `o_cmd` for commands normally coming from ground, and `o_cam` for messages normally coming from the `Camera` component.

```scala
class Test extends Component {
  val i_tck = new Input[Unit]
  val i_obs = new ObsInput
  val i_cam = new Input[Imaging2Camera]
  val o_cmd = new CommandOutput
  val o_cam = new Output[Camera2Imaging]

  ... // to be explained below
}
```

Inside the `Test` component (so we have access to the ports) we introduce the rule-based program. It contains three rules, each sending a message to one of the output ports, with an upper limit on how many messages of each kind can be sent.

```scala
  object TestRules extends Rules {
    val MAX_IMAGES: Int = 1000
    val MAX_SHUTDOWNS: Int = 1000
    val MAX_READY: Int = 1000

    var imageCount: Int = 0
    var shutdownCount: Int = 0
    var readyCount: Int = 0

    rule("TakeImage") (imageCount < MAX_IMAGES) -> {
      o_cmd.invoke((TakeImage(imageCount)))
      imageCount += 1
    }
    
    rule("ShutDown") (shutdownCount < MAX_SHUTDOWNS) -> {
      o_cmd.invoke(ShutDown)
      shutdownCount += 1
    }
    
    rule("Ready") (readyCount < MAX_READY) -> {
      o_cam.invoke(Ready)
      readyCount += 1
    }

    strategy(Pick())
  }
```

The strategy chosen is `Pick` which means: select **one** enabled rule randomly and execute it. The reason for this particular (one at a time) strategy will be explained below.

Note that`TestRules` is defined as an object, and not as a class. We could alternatively have declared it as a class and then instantiated it. 

We also keep our Daut monitor from [README.md](README.md), monitoring that every `TakeImage` command is terminated by a `ImageSaved` or
 `ImageAborted` before the next `TakeImage` is processed:

```scala
  object SaveOrAbort extends Monitor[Observation] {
    always {
      case EvrTakeImage(_) => hot {
        case EvrImageSaved | EvrImageAborted => ok
        case EvrTakeImage(_) => error("Image was not saved or aborted")
      }
    }
  }
```

The `Test` component ends with instructions as to how to direct incoming messages: ticks from the main program causes the rule program to fire, observations are sent to the monitor, and all other messages are ignored.

```scala
  override def when: PartialFunction[Any, Unit] = {
    case _: Unit => TestRules.fire()
    case o: Observation => SaveOrAbort.verify(o)
    case _ =>
  }
}
```

The fact that the `Test` component is driven by the `Main` program with tick messages is the reason that we chose the `Pick` strategy to run the rule program: one tick - one rule fired. This way the `Main` program has control over how slowly the rule program executes its rules.

## The Main Program

The `Main` program instantiates the `Imaging` and the `Test` components, connects their ports, and then repeatedly 1000 times, with 100 ms in between, sends a tick message to the `Test` component, causing a rule to be fired for each tick (the `repeat` function is provided by the Rules DSL).

```scala
object Main {
  def main(args: Array[String]): Unit = {
    FPrimeOptions.DEBUG = true
    HSMOptions.DEBUG = true
    RulesOptions.DEBUG = true

    val imaging = new Imaging
    val test = new Test

    test.o_cmd.connect(imaging.i_cmd)
    test.o_cam.connect(imaging.i_cam)
    imaging.o_cam.connect(test.i_cam)
    imaging.o_obs.connect(test.i_obs)

    repeat(1000) {
      Thread.sleep(100)
      println("=" * 80)
      test.i_tck.invoke(())
    }
  }
}
```

## Test Result: Potential Queuing Problem

Executing the above component system does not reveal any functional correctness violation. This may in part be due to a weak set of requirements: no hard assertions and only one Daut monitor. However, setting the `DEBUG` flags yields output of the form (here commented):

```scala
===============================================================================
[fpr] ? Test : ()                     // Test component receives a tick
[rul] executing rule Ready            // Rule program executes rule Ready
[fpr] ! Test -[Ready]-> Imaging       // Test component sends Ready to Imaging
[fpr] ? Imaging : Ready               // Imaging receives Ready
Ready stored as missed: [Ready]       // Ready is not expected and is stored
================================================================================
[fpr] ? Test : ()                     // Test component receives a tick
[rul] executing rule Ready            // Rule program executes rule Ready
[fpr] ! Test -[Ready]-> Imaging       // Test component sends Ready to Imaging
[fpr] ? Imaging : Ready               // Imaging receives Ready
Ready stored as missed: [Ready,Ready] // Ready is not expected and is stored
================================================================================
...
```

It shows a problem with the handling of _missed events_: events which arrive in the `Imaging` component, but which it is not able to handle in the state it is currently in. These are put in the `Missedevents` queue.  When the imaging HSM gets back to the `off` state, it looks for the next event in the missed-queue. If such a one exists it takes it out and re-submits it. The event, however, may not match what is expected in the `off` state, which is only `TakeImage` events. The result is that the miseed-queue grows and grows with `Ready` and `ShutDown` events.

From a functional correctness point of view, the program works since the `ShutDown` and `Ready` events probably should be ignored in the `off` state anyway. The problem, however, is that the queue of missed events keeps growing. This problem fundamentally is related to the design choice of only having one input queue per component.
