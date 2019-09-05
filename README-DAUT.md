# Monitoring with Data Automata (Daut)

Daut (Data automata) is an internal Scala DSL for writing event stream monitors. It
supports a simple but yet interesting combination of state machines, temporal logic, 
and rule-based programming, all in one unified formalism, implemented in very few lines of code (341 lines not counting comments). The underlying concept is that at any point during monitoring there is an
active set of states, the _"state soup"_. States can be added to this set by taking state to state transitions
(target states are added), and can be removed from this soup by leaving states as a result of transitions. Each state in the soup can itself monitor the incoming event stream, and it can be used to record data (as in rule-based programming).

The specification language specifically supports:

- Automata, represented by states, parameterized with data (thereby the name Daut: Data automata).
- Temporal operators which generate states, resulting in more succinct specifications.
- Rule-based programming in that one can test for the presence of states.
- General purpose programming in Scala when the other specification features fall short.

The DSL is a simplification of the TraceContract 
[https://github.com/havelund/tracecontract](https://github.com/havelund/tracecontract) 
internal Scala DSL, which was used on the [LADEE mission](https://www.nasa.gov/mission_pages/ladee/main/index.html) for checking command sequences against flight rules.

The general idea is to create a monitor as a class sub-classing the `Monitor` class, create an instance of it, and then feed it with events with the `verify(event: Event)` method, one by one, and in the case of a finite sequence of observations, finally calling the `end()` method on it. If `end()` is called, it will be determined whether there are any outstanding obligations that have not been satisfied (expected events that did not occur).

This can schematically be illustrated as follows:

```scala
class MyMonitor extends Monitor[SomeType] {
  ...
}

object Main {
  def main(args: Array[String]) {
    val m = new MyMonitor()
    m.verify(event1)
    m.verify(event2)
    ...
    m.verify(eventN)
    m.end()
  }
}
```

In the following, we shall illustrate the API by going through a collection of examples.
 
## Basic Example

Consider the monitoring of acquisition and release of locks by threads. We shall in other words monitor a sequence of events, where each event indicates either the acquisition of a lock by a thread, or the release of a lock by a thread. We can then formulate various policies about such acquisitions and releases as Daut monitors.

### Events

Let us start by modeling the type `LockEvent` of events:

```scala
trait LockEvent
case class acquire(t:Int, x:Int) extends LockEvent // thread t acquires lock x
case class release(t:Int, x:Int) extends LockEvent // thread t releases lock x
```

### The Property

We can then formulate our first property: 

- _"A task acquiring a lock should eventually release it. At most one task
can acquire a lock at a time"_. 

### The Monitor

This property is stated as the following monitor:

```scala
class AcquireRelease extends Monitor[LockEvent] {
  always {
    case acquire(t, x) =>
      hot {
        case acquire(_,`x`) => error
        case release(`t`,`x`) => ok
      }
  }
}
```

The monitor is formulated as a class `AcquireRelease` extending the `Monitor` class, instantiated with the type of events, `LockEvent`, that we want to monitor.

The body of the `AcquireRelease` monitor class shall read as follows: it is always the case (checked continuously) that if we observe an `acquire(t, x)` event, then we go into a so-called `hot` state (which must be left eventually), where we are waiting for one of two events to occur: either an:

     acquire(_,`x`) 
     
event, where the `x` is the same as was previously acquired (caused by the quotes around `x`), or a: 

     release(`t`,`x`)
     
event, where the thread `t` and the lock `t` are the same as in the original acquisition. In the first case the transition returns the `error` state, and in the second case the transition returns the `ok` state. An `ok` means that we are done monitoring that particular path in the monitor.

### Applying the Monitor

We can now apply the monitor, as for example in the following main program:

```scala
object Main {
  def main(args: Array[String]) {
    val m = new AcquireRelease
    m.verify(acquire(1, 10))
    m.verify(release(1, 10))
    m.end()
  }
}
```

Note the call `m.end()` which terminates monitoring. A monitor does not need to be terminated by this call, and
will not be if for example it concerns an ongoing online monitoring of an executing system. However, if this method is called, it will check that no monitor is in a `hot` state, as shown above. This is in effect how eventuallity properties are checked on finite traces.

### The State Producing Functions

The body `always { ... }` above was perhaps a little bit of a mystery. In reality it is a call of a function with the signature:

```scala
type Transitions = PartialFunction[LockEvent, Set[state]]

def always(ts: Transitions): state
```

That is, `always` is a function that as argument takes a partial function from events to sets of states, and returns a state. Partial functions in Scala are represented as sequences of **case** statements of the form:

```scala
{
  case pattern_1 => code_1
  case pattern_2 => code_2
  ...
  case pattern_n => code_n
}
```
    
and this is exactly how we model transitions out of a state. The `always` function therefore returns a state with these transitions leaving it. In addition, since it is an always-state, it has a self loop back to itself, so taking a transition returns the state itself in addition to whatever the chosen transition produces as a set of states.

Daut offers a collection of such functions, each returning a state using the provided transition function according to its semantics. All states are final states except those produced by the functions `hot` and `next`, which are non-final states. Being in a non-final states results in an error when `end()` is called.

```scala
def always(ts: Transitions): state // always check the transitions
def watch(ts: Transitions): state  // watch until one of the transitions fire and move on
def hot(ts: Transitions): state    // non-final, otherwise same meaning as watch
def next(ts: Transitions): state   // non-final, one of the transitions must fire next
def wnext(ts: Transitions): state  // one of the transtions must fire next, if there is a next event
```

### From States to Sets of States and Other Magic

You notice above that the state producing functions each takes a partial function of type `Transitions` as argument, that is, of type:

```scala
PartialFunction[LockEvent, Set[state]]
```

Such a partial function returns a **set** of states. In the above example, repeated here, however, 
we see that single states are returned, namely `hot {...}`, `error`, and `ok`. 

```scala
always {
  case acquire(t, x) =>
    hot {
      case acquire(_,`x`) => error
      case release(`t`,`x`) => ok
    }
}
```

How does that work? - a state is not a set of states! An implicit function handles such cases, lifting (behind our back) any single state `S` to the set `Set(S)`, to make the program type check, and work: 

```scala
implicit def convState2StateSet(state: state): Set[state] = Set(state)
```

Other such implicit functions exist, for example the following two, which respectively allow us to write code with side effects as the target of a transition, which will result in an `ok` state, or writing a Boolean expression, which will result in `ok` or `error` state depending on what the Boolean expression evaluates to:

```scala
implicit def convUnit2StateSet(u: Unit): Set[state] = Set(ok)
implicit def convBoolean2StateSet(b: Boolean): Set[state] = Set(if (b) ok else error)
```

### How Did the Initial State Get Recorded?

You may wonder how the state procuced by the `always` function ends up being monitored. After all, it is just a call of a function that returns a state. It happens conveniently that the first state created in a monitor becomes the initial state of the monitor (a side effect).
                                                                                
## Naming the Intermediate State via a Function Call

We have already gotten a good sense of the temporal logic flavor of the Daut logic. However, Daut also supports state machine notation. Suppose we want to modify the above monitor to explicitly name the hot state where we have received a lock acquisition, but where we are waiting for a release, as one would do in a state machine. This can be done by simply defining the hot state as the body of a state returning function, and then call that function, as is done in the following:

```scala
class AcquireRelease extends Monitor[LockEvent] {
  always {
    case acquire(t, x) => acquired(t, x)
  }

  def acquired(t: Int, x: Int) : state =
    hot {
      case acquire(_,`x`) => error("lock acquired before released")
      case release(`t`,`x`) => ok
    }
}
```

There is no magic to this: the function call `acquired(t, x)` simply returns the hot state that we had
previously inlined. 

The previous temporal version is shorter and might be preferable. Naming states, however, can bring conceptual
clarity to sub-concepts.

This "state machine" contains no loops. The next example introduces a looping state machine, with data.

## State Machines using Functions that Return States

In this example, we shall illustrate a state machine with a loop, using functions to represent the individual
states, which by the way in this case are parameterized with data, a feature not supported by text book state machines, including extended state machines.

### The Events

We are monitoring a sequence of `start` and `stop` events, each carrying a task id as parameter:

```scala
trait TaskEvent
case class start(task: Int) extends TaskEvent
case class stop(task: Int) extends TaskEvent
```

### The Property

The property we want to monitor is the following: 

- _"Tasks should be executed (started and stopped) in increasing order according to task numbers, starting from task 0, with no other events in between, hence: 
start(0),stop(0),start(1),stop(1),start(2),stop(2),... A started task should eventually be stopped"_.

### The Monitor

This following monitor verifies this property, and illustrates the use of next and weak next states.

```scala
class StartStop extends Monitor[TaskEvent] {
  def start(task: Int) : state =
    wnext {
      case start(`task`) => stop(task)
    }

  def stop(task: Int) : state =
    next {
      case stop(`task`) => start(task + 1)
    }

  start(0) // initial state
}
```

### The Main Program

The following main program exercises the monitor.

```scala
object Main {
  def main(args: Array[String]) {
    val m = new StartStop
    m.PRINT = true
    m.verify(start(0))
    m.verify(stop(0))
    m.verify(start(1))
    m.verify(stop(1))
    m.verify(start(3)) // violates property
    m.verify(stop(3))
    m.end()
  }
}
```

## States as Case Classes

Above we saw how state machines can be modeled using state-returning functions. There is an alternative way of modeling states, which becomes useful when we want to use techniques known from rule-based programming. In rule-based programming one can query whether a particular state is in the _state soup_, as a condition to taking a transition. This is particularly useful for modeling properties reasoning about the past (the past is stored as states). In order to do that, we need to make states objects (of case classes). This technique can be used as a general technique for modeling state machines, but is only strictly needed when quering states in this manner.

### The Property

Let's go back to our lock acquisition and release scenario, and formulate the following property:

- _"A task acquiring a lock should eventually release it. At most one task
can acquire a lock at a time. A task cannot release a lock it has not acquired."_.

It is the last requirement _"A task cannot release a lock it has not acquired"_, that is a past time property: if a task is released, it must have been acquired in the past, and not released since. 

### The Monitor

The monitor can be formulated as follows:

```scala
class AcquireRelease extends Monitor[LockEvent] {
  case class Locked(t: Int, x: Int) extends state {
    hot {
      case acquire(_, `x`) => error
      case release(`t`, `x`) => ok
    }
  }

  always {
    case acquire(t, x) => Locked(t, x)
    case release(t, x) if !Locked(t, x) => error
  }
}
```

The monitor declares a **case** class `Locked`. An instance `Locked(t, x)` of this class is a state (the `Locked` class extends the `state` class) and is meant to represent the _fact_ (rule-based terminology) that thread `t` has acquired the lock `x`. A state `Locked(t, x)` is created and added to the _state soup_ upon the observation of an
`acquire(t, x)` event in the always active `always` state. Note how the state itself this time checks whether it gets double acquired by some other thread (underscore pattern means that we don't care about the thread),
and if released, resulting in the `ok` state, goes away.

Now, the same `always` state producing the `Locked(t, x)` state also contains the transition:

```scala
case release(t, x) if !Locked(t, x) => error
```

that tests for the occurrence of a `Locked(t, x)` state (fact) in the _state soup_, and if not present in case of a 
`release(t, x)` event yields an error. Here a little implicit function magic is occurring. The term 
`!Locked(t, x)` is the negation of the term `Locked(t, x)`, which itself is an object of type state. This is all made to work by the exisistence of the following implicit function, which lifts a `state` object to a Boolean,
being true only if the object is in the _state soup_, represented by the variable _states_:

```scala
implicit def convState2Boolean(s: state): Boolean = states contains s 
```

### Two Kinds of State Producing Functions.

In the `Locked(t: Int, x: Int)` case class above, we saw a call of a `hot` function. Although it looks like
the `hot` function we saw earlier, it is actually a different one, not returning a state, but updating the state
it is called from within. That is, the `state` class provides the following functions, analog to the previous ones,
but which just updates the state's transition function (return type is `Unit` and not `state`):

```scala
def always(ts: Transitions): Unit // always check the transitions
def watch(ts: Transitions): Unit  // watch until one of the transitions fire and move on
def hot(ts: Transitions): Unit   // non-final, otherwise same meaning as watch
def next(ts: Transitions): Unit   // non-final, one of the transitions must fire next
def wnext(ts: Transitions): Unit  // one of the transtions must fire next, if there is a next event
```

A user does not need to think about this distinction between the two sets of functions,
once the specfication patterns have become familiar.

## The Start-Stop State Machine using Rules

In this example we illustrate, using the start-stop example, how case classes can be used to represent a state machine with a loop. This is an alternative to the use of functions we saw earlier. 

```scala
class TestMonitor extends Monitor[TaskEvent] {

  case class Start(task: Int) extends state {
    wnext {
      case start(`task`) => Stop(task)
    }
  }

  case class Stop(task: Int) extends state {
    next {
      case stop(`task`) => Start(task + 1)
    }
  }

  Start(0)
}
```

Both solutions in this case work equally well from a monitoring point of view. 
However, the use of case classes has one advantage: if we turn on printing mode,
setting a monitor's `PRINT` flag to true (see below), then case class states will be printed nicely,
whereas this is not the case when using anonymous states (using the function approach).

## Verifying Real-Time Properties

Daut supports monitoring of some forms of timing properties. We shall consider the lock
acquisition and release scenario again, but slightly modified such that events carry a time stamp. We shall then formulate a property about these time stamps.

### The Events

The events now carry an additional time stamp `ts` indicating when the lock was acquired, respectively released:

```scala
trait LockEvent
case class acquire(t:Int, x:Int, ts:Int) extends LockEvent
case class release(t:Int, x:Int, ts:Int) extends LockEvent
```

### The Property

The property now states that locks should be released in a timely manner:

- _"Property ReleaseWithin: A task acquiring a lock should eventually release
it within 500 milliseconds."_

### The Monitor

The monitor can be formulated as a monitor class parameterized with the number of time
units within which a lock must be released after it has been acquired:

```scala
class ReleaseWithin(limit: Int) extends Monitor[LockEvent] {
  always {
    case acquire(t, x, ts1) =>
      hot {
        case release(`t`,`x`, ts2) => ensure(ts2 - ts1 <= limit)
      }
  }
}
```

The function `ensure(b: Boolean): state` returns either the `ok` state or the `error` state, depending on whether the Boolean condition `b` is true or not.

This can actually be expressed slightly more elegantly, leaving out the call of `ensure` and just provide its argument, as in:

```scala
class ReleaseWithin(limit: Int) extends Monitor[LockEvent] {
  always {
    case acquire(t, x, ts1) =>
      hot {
        case release(`t`,`x`, ts2) => ts2 - ts1 <= limit
      }
  }
}
```

This is due to the before mentioned implicit function:

```scala
implicit def convBoolean2StateSet(b: Boolean): Set[state] = Set(if (b) ok else error)
```

Finally, it should be mentioned that one can call the function 
`check(b: Boolean): Unit` at any point. It will report an error in case the Boolean
condition `b` is false, but otherwise will let the monitor progress as if no error had occurred.

### The Main Program

Finally, we can instantiate the monitor:-

```scala
object Main {
  def main(args: Array[String]) {
    val m = new ReleaseWithin(500) printSteps()
    m.verify(acquire(1, 10, 100))
    m.verify(release(1, 10, 800)) // violates property
    m.end()
  }
}
```          

PS: note the call of `printSteps()` on the monitor (which returns the monitor). It has the same effect as adding `m.PRINT = true`.

### Limitations of this Approach

The above described approach to the verification of timing properties is limited in the sense that it cannot detect a timing violation as soon as it occurs. For example, after an `acquire` event, if no `release` event occurs within the time window, the monitor will not know until either a proper `release` occurs, perhaps much later, or until the `end()` method is called and we find ourselves in the hot state. This can only be improved upon by e.g. introducing other more regularly occuring events containing time stamps and then include those in the specification. The TraceContract system has timers internal to the monitor, but Daut does currently not support this.

## Code in Specs and Invariants

This example illustrates the use of Scala code as part of a specification, and the use of
class invariants, using the lock acquisition and release scenario again. First we reformulate
the property we want to monitor.

### The Property

- _"A task acquiring a lock should eventually release it. At most one task can acquire a lock at a time. At most 4 locks should be acquired at any moment"_.

### The Monitor

The monitor declares a monitor local integer variable `count`, keeping track of the number
of un-released locks. A monitor class invariant expresses the upper limit on the number of
un-released locks.

```scala
class AcquireReleaseLimit extends Monitor[LockEvent] {
  var count : Int = 0

  invariant {count <= 4}

  always {
    case acquire(t, x) =>
      count +=1
      hot {
        case acquire(_,`x`) => error
        case release(`t`,`x`) => count -= 1; ok
      }
  }
}
```

The `invariant` function takes as argument a Boolean expression (call by name) and ensures that this expression is evaluated after each event processed by the monitor. If the expression ever becomes false, an error is issued.

The example illustrates how the temporal logic can be combined with programming. This paradigm of combining programming and temporal logic/state machines can of course be carried much furher.

## Multiple Monitors

Monitors can be combined into a single monitor. For example, the following is possible:

```scala
class AcquireRelease extends Monitor[Event] {
  always {
    case acquire(t, x) =>
      hot {
        case acquire(_,`x`) => error
        case release(`t`,`x`) => ok
      }
  }
}

class ReleaseAcquired extends Monitor[Event] {
  case class Locked(t:Int, x:Int) extends state {
    watch {
      case release(`t`,`x`) => ok
    }
  }

  always {
    case acquire(t,x) => Locked(t,x)
    case release(t,x) if !Locked(t,x) => error
  }
}

class Monitors extends Monitor[Event] {
  monitor(new AcquireRelease, new ReleaseAcquired) // monitor sub-monitors
}

object Main {
  def main(args: Array[String]) {
    val m = new Monitors // now monitoring two sub-monitors
    m.verify(acquire(1, 10))
    m.verify(release(1, 10))
    m.end()
  }
}
```

This allows to build a hierarchy of monitors, which might be useful for grouping.

## How to React to Errors

Error handing in monitors can be done in a number of ways.

### Checing on Final Error Count

Once monitoring is done (assuming `end()` is called, but even before), one can call
the `getErrorCount` method on the monitor to determine how many errors occurred, and
if any, perform a proper action:

```scala
object Main {
  def main(args: Array[String]) {
    val m = new Monitors // now monitoring two sub-monitors
    m.verify(acquire(1, 10))
    m.verify(release(1, 10))
    m.end()
    if (m.getErrorCount > 0) { /* action */ }
  }
}
```

### Writing Error Handling Code in Transitions

One can of course write error handling code in the transitions themselves, as in:

```scala
class AcquireRelease extends Monitor[Event] {
  def handleError() { /* action */ }

  always {
    case acquire(t, x) =>
      hot {
        case acquire(_,`x`) => 
          handleError() // handle the error
          error // and return the error state
        case release(`t`,`x`) => ok
      }
  }
}
```

### Using Callback Function

Finally, one can override the `callBack()` method defined in the `Monitor` class.
This method will be automatically called upon detection of an error. It does not need to
be called explicitly.

```scala
class AcquireRelease extends Monitor[Event] {
  override def callBack() { /* action */ }

  always {
    case acquire(t, x) =>
      hot {
        case acquire(_,`x`) => 
          handleError() // handle the error
          error // and return the error state
        case release(`t`,`x`) => ok
      }
  }
}

```

## Options

A Daut monitor has three option variables that can be set:

- PRINT : when set to true, causes each monitor step to be printed, including event and resulting set of states. Default is false.
- PRINT_ERROR_BANNER : when set to true, when an error occurs, a very big ERROR BANNNER is printed (to make it visible amongst plenty of output). Default is false.
- STOP_ON_ERROR: when set to true an error will case the monitor to stop. Default is false.

These options can be set in two manners, either by assigning to these variables:

```scala
val m = MyMonitor()
m.PRINT = true
m.PRINT_ERROR_BANNER = false
m.STOP_ON_ERROR = true
```
