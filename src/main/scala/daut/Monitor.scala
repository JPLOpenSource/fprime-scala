package daut

/**
 * Daut (Data automata) is an internal Scala DSL for writing event stream monitors. It
 * supports a combination of state machines and temporal logic as specification language.
 * However, in general, the specification languages includes all of Scala. The monitors are able to
 * monitor events that carry data and relate such from different events across time, using any
 * of Scala's expressions, hence the 'D' in Daut. A special convenient capability is the support
 * of a collection of temporal operators, which allows to avoid naming all intermediate states in an automaton,
 * resulting in more succinct monitors having the flavor of temporal logic. Furthermore, states in
 * a state machine can be parameterized with data. The DSL is a simplification of the TraceContract
 * internal Scala DSL by an order of magnitude less code.
 *
 * The general idea is to create a monitor as a class sub-classing the <code>Monitor</code> class,
 * create an instance of it, and then feed it with events with the <code>verify(event: Event)</code> method,
 * one by one, and in the case of a finite sequence of observations, finally calling the
 * <code>end()</code> method on it. If <code>end()</code> is called, it will be determined whether
 * there are any outstanding obligations that have not been satisfied (expected events that did not occur).
 *
 * This can schematically be illustrated as follows:
 *
 * <code>
 * class MyMonitor extends Monitor[SomeType] {
 * ...
 * }
 *
 * val m = new MyMonitor()
 *   m.verify(event1)
 *   m.verify(event2)
 * ...
 *   m.verify(eventN)
 *   m.end()
 * </code>
 */

/**
 * If the <code>STOP_ON_ERROR</code> flag is set to true, a <code>MonitorError</code> exception is thrown
 * if a monitor's specification is violated by the observed event stream.
 */

case class MonitorError() extends RuntimeException

/**
 * Any monitor must sub-class this class. It provides all the DSL constructs for writing
 * a monitor.
 *
 * @tparam E the type of events submitted to the monitor.
 */

class Monitor[E] {
  /**
   * The name of the monitor, derived from its class name.
   */

  private val monitorName = this.getClass().getSimpleName()

  /**
   * A monitor can contain sub-monitors in a hierarchical manner. Any event submitted to the monitor will
   * also be submitted to the sub-monitors. This allows for better organization of many monitors. The effect,
   * however, is the same as defining all monitors flatly at the same level.
   */

  private var monitors: List[Monitor[E]] = List()

  /**
   * A monitor is at any point in time in zero, one, or more states, represented as a set of states. All
   * states have to lead to success, hence there is an implicitly understood conjunction between
   * the states in the set.
   */

  private var states: Set[state] = Set()

  /**
   * This variable holds invariants that have been defined by the user with one of the
   * <code>invariant</code> methods. Invariants are Boolean valued functions that are
   * evaluated after each submitted event has been processed by the monitor. An invariant
   * can e.g. check the values of variables declared local to the monitor. The violation of an
   * invariant is reported as an error.
   */

  private var invariants: List[(String, Unit => Boolean)] = Nil

  /**
   * For each submitted event this set contains all states that are to be removed
   * from the set <code>states</code> of active states. A state needs to be removed
   * when leaving the state due to a fired transition.
   */

  private var statesToRemove: Set[state] = Set()

  /**
   * For each submitted event this set contains all states that are to be added
   * to the set <code>states</code> of active states. A state needs to be added
   * when entering the state due to a fired transition.
   */

  private var statesToAdd: Set[state] = Set()

  /**
   * A monitor's body consists of a sequence of state declarations. The very first state
   * will become the initial state. This variable is used to keep track of when this
   * first state has been added as initial state, whereupon it is set to false, such that
   * subsequent states are not added as initial states.
   */

  private var first: Boolean = true

  /**
   * Number of violation of the specification encountered.
   */

  private var errorCount = 0

  /**
   * Option, which when set to true will cause debugging information to be printed during
   * monitoring, such as for each processed event: the event and the resulting set of states.
   * Default value is false.
   */

  var PRINT: Boolean = false

  /**
   * Option, which when set to true will cause a big ERROR banner to be printed
   * on standard out upon detection of a specification violation, making it easier
   * to quickly see that an error occurred amongst other output.
   * Default value is true.
   */

  var PRINT_ERROR_BANNER = true

  /**
   * Option, which when set to true will cause monitoring to stop the first time
   * a specification violation is encountered. Otherwise monitoring will continue.
   * Default value is false.
   */

  var STOP_ON_ERROR: Boolean = false

  /**
   * Launches the monitors provided as var-argument as sub-monitors of this monitor.
   * Being a sub-monitor has no special semantics, it is just a way of grouping
   * monitors in a hierarchical manner for organization purposes.
   *
   * @param monitors the monitors to become sub-monitors of this monitor.
   */

  def monitor(monitors: Monitor[E]*) {
    this.monitors ++= monitors
  }

  /**
   * A call of this method will cause the events and monitor+sub-monitor states to be printed in each step.
   *
   * @return the monitor itself so that the method can be called dot-appended to a constructor call.
   */

  def printSteps(): Monitor[E] = {
    PRINT = true
    this
  }

  /**
   * A call of this method will cause the monitor and all its sub-monitors to stop on the first error encountered.
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

  /**
   * The type of the partial function from events to sets of states representing
   * the transitions out of a state. Note that a state transition can result in more
   * than one state: all resulting states will subsequently be explored in parallel,
   * and all must be satisfied by the subsequent sequence of events.
   */

  protected type Transitions = PartialFunction[E, Set[state]]

  /**
   * Partial function representing the empty transition function, not defined
   * for any events. Used to initialize the transition function of a state.
   *
   * @return the empty transition function not defined for any events.
   */

  private def noTransitions: Transitions = {
    case _ if false => null
  }

  /**
   * Constant representing the empty set of states.
   */

  private val emptyStateSet: Set[state] = Set()

  /**
   * Invariant method which takes an invariant Boolean valued expression (call by name)
   * as argument and adds the corresponding lambda abstraction (argument of type <code>Unit</code>)
   * to the list of invariants to check after each submission of an event.
   *
   * @param inv the invariant expression to be checked after each submitted event.
   */

  protected def invariant(inv: => Boolean): Unit = {
    invariants ::= ("", ((x: Unit) => inv))
    check(inv, "")
  }

  /**
   * Invariant method which takes an invariant Boolean valued expression (call by name)
   * as argument and adds the corresponding lambda abstraction (argument of type <code>Unit</code>)
   * to the list of invariants to check after each submission of an event. The first argument
   * is a message that will be printed in case the invariant is violated.
   *
   * @param e   message to be printed in case of an invariant violation.
   * @param inv the invariant expression to be checked after each submitted event.
   */

  protected def invariant(e: String)(inv: => Boolean): Unit = {
    invariants ::= (e, ((x: Unit) => inv))
    check(inv, e)
  }

  /**
   * A state of the monitor.
   */

  protected trait state {
    /**
     * The transitions out of this state, represented as an (initially empty) partial
     * function from events to sets of states.
     */

    private[daut] var transitions: Transitions = noTransitions

    /**
     * This variable is true for final (acceptance) states: that is states where it is
     * acceptable to end up when the <code>end()</code> method is called. This corresponds to
     * acceptance states in standard automaton theory.
     */

    private[daut] var isFinal: Boolean = true

    /**
     * Applies the state of an event. If the transition function associated with the state
     * can fire, the resulting state set <code>ss</code> is returned as <code>Some(ss)</code>.
     * If the transition function cannot fire <code>None</code> is returned.
     *
     * @param event the event the state is applied to.
     * @return the optional set of states resulting from taking a transition.
     */

    def apply(event: E): Option[Set[state]] =
      if (transitions.isDefinedAt(event))
        Some(transitions(event)) else None

    if (first) {
      states += this
      first = false
    }

    /**
     * The standard <code>toString</code> method overridden.
     *
     * @return text representation of state.
     */

    override def toString: String = "some state" // needs improvement.
  }

  /**
   * Special state indicating successful termination.
   */

  protected case object ok extends state

  /**
   * Special state indicating a specification violation.
   */

  protected case object error extends state

  /**
   * Returns an <code>error</code> state indicating a specification violation.
   *
   * @param msg message to be printed on standard out.
   * @return the <code>error</code> state.
   */

  protected def error(msg: String): state = {
    println("\n*** " + msg + "\n")
    error
  }

  /**
   * The during-state takes two var-arg lists, {{{es1}}} and {{{es2}}} of events, and
   * returns a state that continuously (always) monitors whether we are in an interval
   * where an event in {{{es1}}} has occurred, but an event in {{{es2}}} has not yet occurred.
   * The state is usually assigned to a local {{{val}}}-variable that can be queried
   * e.g. in invariants, either simply using the during state as a Boolean (the state is lifted to a Booelean
   * with an implicit function) or by using the {{{==>}}} method. Consider the following
   * example illustrating a monitor that checks that at most one of two threads 1 and 2
   * are in a critical section at any time, using an invariant. A thread {{{x}}} can enter a critical
   * section with the {{{enter(x)}}} call, and leave with either an {{{exit(x)}}} call or an
   * {{{abort(x)}}} call.
   *
   * {{{
   * class CriticalSectionMonitor extends Monitor[Event] {
   *   val critical1 = during(enter(1))(exit(1), abort(1))
   *   val critical2 = during(enter(2))(exit(2), abort(1))
   *
   *   invariant {
   *     !(critical1 && critical2)
   *   }
   * }
   * }}}
   *
   * The invariant can also be written as follows, using the {{{==>}}} method:
   *
   * {{{
   *   invariant {
   *     critical1 ==> !critical2
   *   }
   * }}}
   *
   * @param es1 any of these events starts an interval.
   * @param es2 any of these events ends an interval.
   */

  protected case class during(es1: E*)(es2: E*) extends state {
    /**
     * The set of events starting an interval.
     */

    private val begin = es1.toSet

    /**
     * The set of events ending an interval.
     */

    private val end = es2.toSet

    /**
     * This variable is true when we are within an interval.
     */

    private[daut] var on: Boolean = false

    /**
     * This method allows us, given a during-state {{{dur}}}, to write a Booelean
     * expression of the form {{{dur ==> condition}}}, meaning: if {{{dur}}} is in the interval
     * then the {{{condition}}} must hold.
     *
     * @param b the condition that must hold if the during-state is within the interval.
     * @return true if the during state is not within an interval, or if the condition {{{b}}} holds.
     */

    def ==>(b: Boolean) = {
      !on || b
    }

    /**
     * 
     *
     * @return
     */

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

  protected implicit def liftInterval(iv: during): Boolean = iv.on

  /**
   * Returns a watch-state, where the transition function is exactly the transition function provided.
   * This corresponds to a state where the monitor is just waiting (watching) until an event
   * is submitted that makes a transition fire. The state is final.
   *
   * @param ts the transition function.
   * @return a watch-state.
   */

  protected def watch(ts: Transitions) = new state {
    transitions = ts
  }

  /**
   * Returns an always-state, where the transition function is the transition function provided,
   * modified to always include the state in the resulting state set of any transition.
   * This corresponds to a state where the monitor is always waiting  until an event
   * is submitted that makes a transition fire, and where the state has a true
   * self loop, no matter what transition fires. The state is final.
   *
   * @param ts the transition function.
   * @return a always-state.
   */

  protected def always(ts: Transitions) = new state {
    transitions = ts andThen (_ + this)
  }

  /**
   * Returns a hot-state, where the transition function is the transition function provided.
   * This corresponds to a state where the monitor is just waiting (watching) until an event
   * is submitted that makes a transition fire. The state is non-final, meaning
   * that it is an error to be in this state on a call of the <code>end()</code> method.
   *
   * @param ts the transition function.
   * @return a hot-state.
   */

  protected def hot(ts: Transitions) = new state {
    transitions = ts
    isFinal = false
  }

  /**
   * Returns a wnext-state (weak next), where the transition function is the transition function provided,
   * modified to yield an error if it does not fire on the next submitted event.
   * The transition is weak in the sense that a next event does not have to occur (in contrast to strong next).
   * The state is therefore final.
   *
   * @param ts the transition function.
   * @return a wnext-state.
   */

  protected def wnext(ts: Transitions) = new state {
    transitions = ts orElse { case _ => error }
  }

  /**
   * Returns a next-state (strong next), where the transition function is the transition function provided,
   * modified to yield an error if it does not fire on the next submitted event.
   * The transition is strong in the sense that a next event has to occur.
   * The state is therefore non-final.
   *
   * @param ts the transition function.
   * @return a next-state.
   */

  protected def next(ts: Transitions) = new state {
    transitions = ts orElse { case _ => error }
    isFinal = false
  }

  /**
   * An expression of the form <code>unless {ts1} watch {ts2}</code> watches <code>ts2</code> repeatedly
   * unless <code>ts1</code> fires. That is, the expression returns an unless-state, where the transition function is
   * the combination of the two transition functions provided. The resulting transition function
   * first tries <code>ts1</code>, and if it can fire that is chosen. Otherwise <code>t2</code> is tried,
   * and if it can fire it is made to fire, and the unless-state is re-added to the resulting state set.
   * The transition function <code>ts1</code> does not need to ever fire, which makes the state final.
   *
   * @param ts1 the transition function.
   * @return an unless-state.
   */

  protected def unless(ts1: Transitions) = new {
    def watch(ts2: Transitions) = new state {
      transitions = ts1 orElse (ts2 andThen (_ + this))
    }
  }

  /**
   * An expression of the form <code>until {ts1} watch {ts2}</code> watches <code>ts2</code> repeatedly
   * until <code>ts1</code> fires. That is, the expression returns an until-state, where the transition function is
   * the combination of the two transition functions provided. The resulting transition function
   * first tries <code>ts1</code>, and if it can fire that is chosen. Otherwise <code>t2</code> is tried,
   * and if it can fire it is made to fire, and the unless-state is re-added to the resulting state set.
   * The transition function <code>ts1</code> will need to eventually ever fire before <code>end()</code> is
   * called, which makes the state non-final.
   *
   * @param ts1 the transition function.
   * @return an unless-state.
   */

  protected def until(ts1: Transitions) = new {
    def watch(ts2: Transitions) = new state {
      transitions = ts1 orElse (ts2 andThen (_ + this))
      isFinal = false
    }
  }

  protected def exists(pred: PartialFunction[state, Boolean]): Boolean = {
    states exists (pred orElse { case _ => false })
  }

  protected type StateTransitions = PartialFunction[state, Set[state]]

  protected def find(ts1: StateTransitions) = new {
    def orelse(otherwise: => Set[state]): Set[state] = {
      val matchingStates = states filter (ts1.isDefinedAt(_))
      if (!matchingStates.isEmpty) {
        (for (matchingState <- matchingStates) yield ts1(matchingState)).flatten
      } else
        otherwise
    }
  }

  protected def ensure(b: Boolean): state = {
    if (b) ok else error
  }

  protected def check(b: Boolean): Unit = {
    if (!b) reportError()
  }

  protected def check(b: Boolean, e: String): Unit = {
    if (!b) reportError(e)
  }

  protected def initial(s: state) {
    states += s
  }

  protected implicit def convState2Boolean(s: state): Boolean =
    states contains s

  protected implicit def convUnit2StateSet(u: Unit): Set[state] =
    Set(ok)

  protected implicit def convInt2StateSet(d: Int): Set[state] =
    Set(ok)

  protected implicit def convBoolean2StateSet(b: Boolean): Set[state] =
    Set(if (b) ok else error)

  protected implicit def convState2StateSet(state: state): Set[state] =
    Set(state)

  protected implicit def conTuple2StateSet(states: (state, state)): Set[state] =
    Set(states._1, states._2)

  protected implicit def conTriple2StateSet(states: (state, state, state)): Set[state] =
    Set(states._1, states._2, states._3)

  protected implicit def convList2StateSet(states: List[state]): Set[state] =
    states.toSet

  protected implicit def convState2AndState(s1: state) = new {
    def &(s2: state): Set[state] = Set(s1, s2)
  }

  protected implicit def conStateSet2AndStateSet(set: Set[state]) = new {
    def &(s: state): Set[state] = set + s
  }

  protected implicit def liftBoolean(p: Boolean) = new {
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

  protected def verifyBeforeEvent(event: E) {}

  /**
   * This method is called <b>after</b> every call of <code>verify(event: E)</code>.
   * It can be overridden by user. Its body is by default empty.
   *
   * @param event the event being verified.
   */

  protected def verifyAfterEvent(event: E) {}

  /**
   * This method is called when the monitor encounters an error, be it a safety
   * error or a liveness error.
   * It can be overridden by user. Its body is by detult empty.
   */
  protected def callBack(): Unit = {}

  private def printEvent(event: E) {
    println("\n===[" + event + "]===\n")
  }

  def getErrorCount: Int = {
    var count = errorCount
    for (m <- monitors) count += m.getErrorCount
    count
  }

  private def printStates() {
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

  private def reportError() {
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

  private def reportError(e: String): Unit = {
    println("***********")
    println(s"** ERROR : ${e} **")
    println("***********")
    reportError()
  }
}

