# Daut Tutorial

## Basic Example


Property AcquireRelease: A task acquiring a lock should eventually release it. At most one task
can acquire a lock at a time.

```scala
trait LockEvent
case class acquire(t:Int, x:Int) extends LockEvent
case class release(t:Int, x:Int) extends LockEvent
```

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

```scala
object Main {
  def main(args: Array[String]) {
    val m = new AcquireRelease
    m.PRINT = true
    m.verify(acquire(1, 10))
    m.verify(release(1, 10))
    m.end()
  }
}
```

## Naming the intermediate state via function call

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

## State machines using functions returning states

Tasks should be executed (started and stopped) in increasing order according
to task numbers, staring from task 0: 0, 1, 2, ...

This monitor illustrates next-states (as in Finite State Machines) and
state machines.

```scala
trait TaskEvent
case class start(task: Int) extends TaskEvent
case class stop(task: Int) extends TaskEvent
```

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

  initial(start(0))
}
```

```scala
object Main {
  def main(args: Array[String]) {
    val m = new StartStop
    m.PRINT = true
    m.verify(start(0))
    m.verify(stop(0))
    m.verify(start(1))
    m.verify(stop(1))
    m.verify(start(2))
    m.verify(stop(2))
    m.end()
  }
}
```

## States as case classes

A task acquiring a lock should eventually release it. At most one task
can acquire a lock at a time. A task cannot release a lock it has not acquire.

This monitor illustrates always states, hot states, and the use of a
fact (Locked) to record history. This is effectively in part a past time property.

```scala
class AcquireRelease extends Monitor[LockEvent] {
  case class Locked(thread: Int, lock: Int) extends state {
    hot {
      case acquire(_, `lock`) => error
      case release(`thread`, `lock`) => ok
    }
  }

  always {
    case acquire(t, l)                  => Locked(t, l)
    case release(t, l) if !Locked(t, l) => error
  }
}
```

## There is a choice where to put transitions

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
  monitor(new AcquireRelease, new ReleaseAcquired)
}

object Main {
  def main(args: Array[String]) {
    val m = new Monitors
    m.PRINT = true
    m.verify(acquire(1, 10))
    m.verify(release(1, 10))
    m.end()
  }
}
```

## The start-stop state machine using rules

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