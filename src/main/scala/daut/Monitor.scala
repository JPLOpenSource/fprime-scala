package daut

/**
 * Internal DSL for temporal monitors (Data automata).
 */

case class MonitorError() extends RuntimeException

class Monitor[E] {
  private val monitorName = this.getClass().getSimpleName()
  private var monitors: List[Monitor[E]] = List()
  private var states: Set[state] = Set()
  private var invariants: List[(String, Unit => Boolean)] = Nil
  private var statesToRemove: Set[state] = Set()
  private var statesToAdd: Set[state] = Set()
  private var first: Boolean = true

  var errorCount = 0
  var PRINT: Boolean = false
  var PRINT_ERROR_BANNER = true
  var STOP_ON_ERROR: Boolean = false

  def monitor(monitors: Monitor[E]*) {
    this.monitors ++= monitors
  }

  /**
   * A call of this method will cause the events and monitor+submonitor states to be printed in each step.
   *
   * @return the monitor itself so that the method can be called dot-appended to a constructor call.
   */

  def printSteps(): Monitor[E] = {
    PRINT = true
    this
  }

  /**
   * A call of this method will cause the monitor and all its submonitors to stop on the first error encountered.
   *
   * @return the monitor itself so that the method can be called dot-appended to a constructor call.
   */

  def stopOnError(): Monitor[E] = {
    STOP_ON_ERROR = true
    for (monitor <- monitors) {
      monitor.stopOnError()
    }
    this
  }

  type Transitions = PartialFunction[E, Set[state]]

  def noTransitions: Transitions = {
    case _ if false => null
  }

  val emptyStateSet: Set[state] = Set()

  def invariant(inv: => Boolean): Unit = {
    invariants ::= ("", ((x: Unit) => inv))
    check(inv, "")
  }

  def invariant(e: String)(inv: => Boolean): Unit = {
    invariants ::= (e, ((x: Unit) => inv))
    check(inv, e)
  }

  trait state {
    thisState =>
    private var transitions: Transitions = noTransitions

    private[daut] var isFinal: Boolean = true

    def watch(ts: Transitions) {
      transitions = ts
    }

    def always(ts: Transitions) {
      transitions = ts andThen (_ + this)
    }

    def hot(ts: Transitions) {
      transitions = ts
      isFinal = false
    }

    def wnext(ts: Transitions) {
      transitions = ts orElse { case _ => error }
    }

    def next(ts: Transitions) {
      transitions = ts orElse { case _ => error }
      isFinal = false
    }

    def unless(ts1: Transitions) = new {
      def watch(ts2: Transitions) {
        transitions = ts1 orElse (ts2 andThen (_ + thisState))
      }
    }

    def until(ts1: Transitions) = new {
      def watch(ts2: Transitions) {
        transitions = ts1 orElse (ts2 andThen (_ + thisState))
        isFinal = false
      }
    }

    def apply(event: E): Option[Set[state]] =
      if (transitions.isDefinedAt(event))
        Some(transitions(event)) else None

    if (first) {
      states += thisState
      first = false
    }

    override def toString: String = "temporal operator"
  }

  case object ok extends state

  case object error extends state

  def error(msg: String): state = {
    println("\n*** " + msg + "\n")
    error
  }

  implicit def liftInterval(iv: during): Boolean = iv.on

  case class during(e1: E*)(e2: E*) extends state {
    val begin = e1.toSet
    val end = e2.toSet
    var on: Boolean = false

    def ==>(b: Boolean) = {
      !on || b
    }

    def startsTrue: during = {
      on = true
      this
    }

    always {
      case e =>
        if (begin.contains(e)) {
          on = true
        }
        else if (end.contains(e)) {
          on = false
        }
    }
    initial(this)
  }

  def watch(ts: Transitions) = new state {
    watch(ts)
  }

  def always(ts: Transitions) = new state {
    always(ts)
  }

  def hot(ts: Transitions) = new state {
    hot(ts)
  }

  def wnext(ts: Transitions) = new state {
    wnext(ts)
  }

  def next(ts: Transitions) = new state {
    next(ts)
  }

  def unless(ts1: Transitions) = new {
    def watch(ts2: Transitions) = new state {
      unless(ts1) watch (ts2)
    }
  }

  def until(ts1: Transitions) = new {
    def watch(ts2: Transitions) = new state {
      until(ts1) watch (ts2)
    }
  }

  def exists(pred: PartialFunction[state, Boolean]): Boolean = {
    states exists (pred orElse { case _ => false })
  }

  type StateTransitions = PartialFunction[state, Set[state]]

  def find(ts1: StateTransitions) = new {
    def orelse(otherwise: => Set[state]): Set[state] = {
      val matchingStates = states filter (ts1.isDefinedAt(_))
      if (!matchingStates.isEmpty) {
        (for (matchingState <- matchingStates) yield ts1(matchingState)).flatten
      } else
        otherwise
    }
  }

  def ensure(b: Boolean): state = {
    if (b) ok else error
  }

  def check(b: Boolean): Unit = {
    if (!b) reportError()
  }

  def check(b: Boolean, e: String): Unit = {
    if (!b) reportError(e)
  }

  def initial(s: state) {
    states += s
  }

  implicit def convState2Boolean(s: state): Boolean =
    states contains s

  implicit def convUnit2StateSet(u: Unit): Set[state] =
    Set(ok)

  implicit def convInt2StateSet(d: Int): Set[state] =
    Set(ok)

  implicit def convBoolean2StateSet(b: Boolean): Set[state] =
    Set(if (b) ok else error)

  implicit def convState2StateSet(state: state): Set[state] =
    Set(state)

  implicit def conTuple2StateSet(states: (state, state)): Set[state] =
    Set(states._1, states._2)

  implicit def conTriple2StateSet(states: (state, state, state)): Set[state] =
    Set(states._1, states._2, states._3)

  implicit def convList2StateSet(states: List[state]): Set[state] =
    states.toSet

  implicit def convState2AndState(s1: state) = new {
    def &(s2: state): Set[state] = Set(s1, s2)
  }

  implicit def conStateSet2AndStateSet(set: Set[state]) = new {
    def &(s: state): Set[state] = set + s
  }

  implicit def liftBoolean(p: Boolean) = new {
    def ==>(q: Boolean) = !p || q
  }

  def verify(event: E) {
    verifyBeforeEvent(event)
    if (PRINT) printEvent(event)
    for (sourceState <- states) {
      sourceState(event) match {
        case None =>
        case Some(targetStates) =>
          statesToRemove += sourceState
          for (targetState <- targetStates) {
            targetState match {
              case `error` => reportError()
              case `ok` =>
              case _ => statesToAdd += targetState
            }
          }
      }
    }
    states --= statesToRemove
    states ++= statesToAdd
    statesToRemove = emptyStateSet
    statesToAdd = emptyStateSet
    if (PRINT) printStates()
    invariants foreach { case (e, inv) => check(inv(), e) }
    for (monitor <- monitors) {
      monitor.verify(event)
    }
    verifyAfterEvent(event)
  }

  def end() {
    if (PRINT) println(s"ENDING TRACE EVALUATION FOR $monitorName")
    val hotStates = states filter (!_.isFinal)
    if (!hotStates.isEmpty) {
      println()
      println(s"*** non final $monitorName states:")
      println()
      hotStates foreach println
      reportError()
    }
    for (monitor <- monitors) {
      monitor.end()
    }
  }

  def apply(event: E): Unit = {
    verify(event)
  }

  /**
   * This method is called <b>before</b> every call of <code>verify(event: E)</code>.
   * It can be overridden by user. Its body is by default empty.
   *
   * @param event the event being verified.
   */
  def verifyBeforeEvent(event: E) {}

  /**
   * This method is called <b>after</b> every call of <code>verify(event: E)</code>.
   * It can be overridden by user. Its body is by default empty.
   *
   * @param event the event being verified.
   */
  def verifyAfterEvent(event: E) {}

  /**
   * This method is called when the monitor encounters an error, be it a safety
   * error or a liveness error.
   * It can be overridden by user. Its body is by detult empty.
   */
  def callBack(): Unit = {}

  def printEvent(event: E) {
    println("\n===[" + event + "]===\n")
  }

  def getErrorCount: Int = {
    var count = errorCount
    for (m <- monitors) count += m.getErrorCount
    count
  }

  def printStates() {
    val topline = "--- " + monitorName + ("-" * 20)
    val bottomline = "-" * topline.length
    println(topline)
    for (s <- states) {
      println(s)
    }
    println(bottomline)
    println()
    for (m <- monitors) m.printStates()
  }

  def reportError() {
    errorCount += 1
    if (PRINT_ERROR_BANNER) {
      println(
        s"""
           |███████╗██████╗ ██████╗  ██████╗ ██████╗
           |██╔════╝██╔══██╗██╔══██╗██╔═══██╗██╔══██╗
           |█████╗  ██████╔╝██████╔╝██║   ██║██████╔╝
           |██╔══╝  ██╔══██╗██╔══██╗██║   ██║██╔══██╗
           |███████╗██║  ██║██║  ██║╚██████╔╝██║  ██║
           |╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝
           |
           |$monitorName error # $errorCount
        """.stripMargin)
    }
    callBack()
    if (STOP_ON_ERROR) {
      println("\n*** terminating on first error!\n")
      throw MonitorError()
    }
  }

  def reportError(e: String): Unit = {
    println("***********")
    println(s"** ERROR : ${e} **")
    println("***********")
    reportError()
  }
}

