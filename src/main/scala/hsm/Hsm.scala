package hsm

/*
 * Internal DSL for Hierarchical State Machines (HSMs). HSMs are extended state machines
 * with the addition of super states, as well as enter and exit statements associated with
 * states.
 */

/**
 * The static variables of HSMs.
 */

object HSM {
  /**
   * To be set to true when tracing is desired. Tracing causes
   * printing the trace of state transitions occurring in the HSMs.
   */

  var TRACE : Boolean = false

  /**
   * The execution trace of an HSM. Updated if {{{TRACE}}} is true.
   */

  private var trace : String = "" // TODO: use StringBuffer

  /**
   * Adds astate transition to the trace.
   *
   * @param line the transition in text format.
   */

  def addToTrace(line: String): Unit = {
    trace += line + "\n"
  }

  /**
   * Resets the {{{trace}}} variable.
   */

  def resetTrace(): Unit = {
    trace = ""
  }

  /**
   * Prints the {{{trace}}} variable.
   */

  def printTrace(): Unit = {
    println("===== trace: =====")
    println()
    println(trace)
    println("------------------")
  }
}

/**
 * A hiearchical state machine must be defined as a class subclassing this trait, which
 * provides all the internal DSL methods for defining an HSM.
 *
 * @tparam Event type of events communicated to the state machine. They must all be of the same type,
 *               usually defined as case classes or case objects subclassing this type. However,
 *               it can be instantiated with any type.
 */

trait HSM[Event] {
  /**
   * Name of state machine, derived from class name.
   */

  private val hsmName : String = this.getClass.getSimpleName

  /**
   * Variable containing time consumed, simulating time progress when taking a transition.
   * It is set with the {{{taking(d : Int)}}} method called on the target state. The used
   * time is the return value of the {{{submit(event: Event): Int}}} method, but otherwise
   * serves no purpose.
   */

  private var delay = 0

  /**
   * Cache mapping values of type {{{A}}} to values of type {{{B}}}. If the cash is not defined
   * for an {{{A}}} value, the {{{B}}} value is computed with the {{{calculateIt}}} function. The cache
   * is used for (1) mapping a state to its (possibly inner) initial state, and (2) for mapping
   * an (exit state, enter state) to the pair consisting of the states to exit and
   * the states to enter.
   *
   * @param calculateIt function for calculating the mapping in case it is not in the cache.
   * @tparam A domain of cache.
   * @tparam B image of cache.
   */

  private class Cache[A, B](calculateIt: A => B) {
    private var map: Map[A, B] = Map()

    def get(a: A): B = {
      var b: B = map.getOrElse(a, null.asInstanceOf[B])
      if (b == null) {
        b = calculateIt(a)
        map += (a -> b)
      }
      return b
    }
  }

  /**
   * Type of code occurring as actions on transitions.
   */

  private type Code = Unit => Unit

  /**
   * The target of a transition consists of a target state as well as the
   * code to execute if taking the transition.
   */

  private type Target = (state, Code)

  /**
   * The event dependent behavior of a state is modeled as a partial function
   * from events to targets.
   */

  private type Transitions = PartialFunction[Event, Target]

  /**
   * By default, unless redefined by the user, a state has no transitions.
   */

  private val noTransitions: Transitions = {
    case _ if false => null
  }

  /**
   * The {{{skip}}} value represents code that does nothing. Used for transitions
   * not annotated with user-defined code to execute.
   */

  private val skip: Code = (x: Unit) => {}

  /**
   * The cache mapping (exit state, enter state) to the pair consisting of the states to exit and
   * the states to enter. Needed for executing the respective exit and enter actions.
   */

  private var exitEnterCache = new Cache[(state, state), (List[state], List[state])](getExitEnterStates)

  /**
   * The cache mapping a state to its (possibly inner) initial state. Note that a state may be a
   * super state containing a deeply nested inner initial state.
   */

  private var initialCache = new Cache[state, state](getInitialState)

  /**
   *  Variable holding code to execute when a state is entered. The function is parameterized with
   *  the name of the state entered.
   */

  private var announceStateEntry: String => Unit = { case name => }

  /**
   *  Variable holding code to execute when a state is exited. The function is parameterized with
   *  the name of the state exited.
   */

  private var announceStateExit: String => Unit = { case name => }

  /**
   * Variable holding code to be executed after each event submitted (when the HSM has gone
   * "silent" after having processed the event).
   */

  private var announceQuiescent: Unit => Unit = { case _ => }

  /**
   * When true debugging information is printed as the HSM executes. Can be set by user.
   * The default value is false.
   */

  var PRINT = false

  /**
   * Logs a transition for debugging purposes.
   *
   * @param exitStates the states exited due to the transition.
   * @param event the event triggering the transition.
   * @param enterStates the states entered due to the transition.
   */

  private def recordTransition(exitStates: List[state], event: Event, enterStates: List[state]): Unit = {
    if (PRINT || HSM.TRACE) {
      val exitStateNames = exitStates.map(_.stateName).mkString(",")
      val enterStateNames = enterStates.map(_.stateName).mkString(",")
      val msg = s"$hsmName : [$exitStateNames] --  $event --> [$enterStateNames]"
      if (PRINT) {
        println()
        println(s"--- $msg")
        println()
      }
      if (HSM.TRACE) {
        HSM.addToTrace(msg)
      }
    }
  }

  /**
   * Allows user to associate a callback function to a state. The function will be called
   * when the state is entered, passing the state name as argument. The function can be used
   * for example for debugging purposes.
   *
   * @param callback the callback function, parameterized with the name of the state entered.
   */

  def setAnnounceStateEntry(callback: String => Unit): Unit = { announceStateEntry = callback }

  /**
   * Allows user to associate a callback function to a state. The function will be called
   * when the state is exited, passing the state name as argument. The function can be used
   * for example for debugging purposes.
   *
   * @param callback the callback function, parameterized with the name of the state exited.
   */

  def setAnnounceStateExit(callback: String => Unit): Unit = { announceStateExit = callback }

  /**
   * Allows user to associate a callback function to the HSM, which will be executed after
   * each submission of an event to the HSM, as the last action to be executed in the
   * {{{submit(event: Event) : Int}}} method. The function can be used for
   * example for debugging purposes.
   *
   * @param callback the callback function.
   */

  def setQuiescent(callback: Unit => Unit): Unit = { announceQuiescent = callback }

  /**
   * Lifts a state to a target (state,code) tuple. This is needed for transitions for which
   * no target code is indicated with the {{{exec}}} method.
   *
   * @param s the state to be lifted.
   * @return the pair {{{(s,skip)}}}.
   */

  protected implicit def state2Target(s: state): Target =
    (s, skip)

  /**
   * Lifts a target state of a transition to define additional methods on it, one for indicating how
   * much time the transition takes (used for simulating that transitions take time), and one for
   * associating code to execute when the transition is taken.
   *
   * @param s the target state of a transition to define these methods on.
   * @return the state itself unchanged.
   */

  protected implicit def state2Exec(s: state) = new {
    /**
     * Records the time that taking the transition resulting in this state takes. The time
     * is returned as a value of the {{{submit(event : Event) : Int}}} method. It is
     * up to the user to use this information. It is not otherwise used by the framework.
     *
     * @param d the speculated time this transition takes to execute.
     * @return the original state unchanged.
     */

    def taking(d: Int):state = { delay += d ; s }

    /**
     * Associates code to be executed when transition ending in this state is taken.
     *
     * @param code the code to be executed when transition leading to this state {{{S}}} is taken.
     * @return the target of the transition {{{(S,(x: Unit) => code)}}}, a tuple consisting of
     *         the target state and the code to be executed when taking the transition, represented as a lambda term.
     */

    def exec(code: => Unit) = (s, (x: Unit) => code)
  }

  /**
   * The current state of the HSM.
   */

  var current: state = null

  /**
   * Returns true iff. the name of the current state matches the {{{java.util.regex.Pattern}}}
   * regular expression provided as argument.
   *
   * @param regexp tye regular expression the state name much match for the method to return true.
   * @return true iff. the current state matches the regular expression.
   */

  def inState(regexp: String): Boolean  = {
    current.getSuperStates.exists(_.stateName.matches(regexp)) // can be optimized
  }

  /**
   * Returns true of the HSM's current state has the name that is provided as argument.
   *
   * @param name the name to match exactly.
   * @return true iff. the current state has the name provided as argument.
   */

  def inThisState(name: String): Boolean = {
    current.stateName == name
  }

  /**
   * Prints a header banner.
   */

  private def printHeader(): Unit = {
    println(
      """
        |   _____           _         _    _  _____ __  __
        |  / ____|         | |       | |  | |/ ____|  \/  |
        | | (___   ___ __ _| | __ _  | |__| | (___ | \  / |
        |  \___ \ / __/ _` | |/ _` | |  __  |\___ \| |\/| |
        |  ____) | (_| (_| | | (_| | | |  | |____) | |  | |
        | |_____/ \___\__,_|_|\__,_| |_|  |_|_____/|_|  |_|
      """.stripMargin
    )
  }

  if (PRINT) { printHeader() }

  /**
   * Declares a state as initial in the HSM.
   *
   * @param s the state to be declared as initial in an HSM.
   */

  protected def initial(s: state): Unit = {
    current = s.getInnerMostState
    current.entryCode()
  }

  /**
   * Declares the states of the HSM. This is needed in order to initialize the
   * objects representing the states due to Scala's lazy initialization approach,
   * where an object is only initialized when used for the first time.
   *
   * @param ss the states to be recorded (and therefore initialized)
   */

  protected def states(ss: state*){}

  /**
   * A substitute for calling e {{{submit)(event : Event) : Int}}} method, supporting
   * the more succinct call {{{hsm(e)}}} instead of {{{hsm.submit(e)}}}.
   *
   * @param event the event submitted to the HSM.
   * @return the time the execution of the event takes in simulated time indicated with the method
   *         {{{taken}}}.
   */

  def apply(event: Event): Int = {
    submit(event)
  }

  /**
   * Given a state and an event, this method finds the innermost state, moving inside out
   * from current state to super states, which can trigger on the event - if such a state
   * exists.
   *
   * @param s the current state.
   * @param event the submitted event.
   * @return the innermost state {{{s</code, returned as {{{Some(s)}}},
   *         counted from the current state, which can trigger on the event. If
   *         such a state does not exist {{{None}}} is returned.
   */

  private def findTriggerHappyState(s: state, event: Event): Option[state] =
    if (s.transitions.isDefinedAt(event))
      Some(s)
    else if (s.parent == null)
      None
    else
      findTriggerHappyState(s.parent, event)

  /**
   * Submits an event to the HSM. If the current state or one of its superstates is has a transition
   * that can be triggered by this event, the following will happen:
   * (1) the exit codes of the states to exit are executed,
   * (2) the code on the transition is executed,
   * (3) the enter codes on the states to enter are executed,
   * (4) and we end up in the target state.
   *
   * @param event the event submitted to the HSM.
   * @return the time the execution of the event takes in simulated time indicated with the method
   *         {{{taken}}}.
   */

  def submit(event: Event): Int = {
    delay = 0
    findTriggerHappyState(current, event) match {
      case None =>
      case Some(triggerState) =>
        val (transitionState, transitionCode) = triggerState.transitions(event)
        val targetState = initialCache.get(transitionState)
        val (exitStates, enterStates) = exitEnterCache.get((current, targetState))
        recordTransition(exitStates, event, enterStates)
        val isSelfTransition = ((enterStates.length == 1) && enterStates(0) == stay)
        if (!isSelfTransition) {
          for (s <- exitStates) { s.exitCode(); announceStateExit(s.stateName) }
        }
        transitionCode()
        if (!isSelfTransition) {
          for (s <- enterStates) { announceStateEntry(s.stateName); s.entryCode() }
          current = targetState
        }
        announceQuiescent()
    }
    delay
  }

  /**
   * Given a state {{{s1}}} to exit and a (final) state {{{s2}}} to enter, this method
   * returns a pair consisting of (1) all the super states of {{{s1}}} from inside out to exit, and (2) all
   * the super states of {{{s2}}} from outside in to enter. The result is used to execute the exit
   * codes of the states to exit and the enter codes of the states to enter. Note that a state that is a common
   * super state of both {{{s1}}} and {{{s2}}} will not be included in the resulting lists.
   *
   * @param fromTo the pair of states {{{(s1,s2)}}} to exit respectively enter.
   * @return the pair of lists of states to exit respectively enter.
   */

  private def getExitEnterStates(fromTo: (state, state)): (List[state], List[state]) = {
    val (from, to) = (fromTo._1, fromTo._2)
    val fromSuperStates = from.getSuperStates
    val toSuperStates = to.getSuperStates
    val (exitTopDown, entryTopDown) =
      if (from == to) {
        (List(from), List(to))
      } else {
        stripCommonPrefix(fromSuperStates, toSuperStates)
      }
    val exitBottomUp = exitTopDown.reverse
    (exitBottomUp, entryTopDown)
  }

  /**
   * Returns for a given state the innermost initial state it contains, itself if there
   * are no substates.
   *
   * @param s the state for which an inner initial state is searched.
   * @return the innermost initial state.
   */

  private def getInitialState(s: state): state =
    s.getInnerMostState

  /**
   * Given a state {{{s1}}} to exit and a (final) state {{{s2}}} to enter as result of
   * a transition, part of executing all necessary exit codes and enter codes is to first determine
   * which states to exit and which states to enter. This method takes as argument all the super states {{{ss1}}}
   * of {{{s1}}}, top down, and all the super states {{{ss2}}} of {{{s2}}}, top down,
   * and strips the super states that are common in {{{ss1}}} and {{{ss2}}}. Note that a state
   * that is a common super state of both {{{s1}}} and
   * {{{s2}}} will not be included in the resulting lists.
   *
   * @param ss1 the list of super states (ordered top down) of the state to exit.
   * @param ss2 the list of super states (ordered top down) of the state to enter.
   * @return the lists {{{(ss1',ss2')}}} where the common prefix of common super states have been
   *         removed.
   */

  private def stripCommonPrefix(ss1: List[state], ss2: List[state]): (List[state], List[state]) = {
    if (ss1 == Nil && ss2 == Nil)
      (Nil, Nil)
    else if (ss1.head.eq(ss2.head))
      stripCommonPrefix(ss1.tail, ss2.tail)
    else
      (ss1, ss2)
  }

  /**
   * The current state of an HSM is an object (instance) of this class. The class provides
   * various methods for defining a state, such as the transitions out of the state
   * as well as methods for defining entry and exit actions to be executed when entering
   * respectively exiting the state.
   *
   * @param parent the parent state of this state, if a such exists.
   * @param init if a parent state is provided, this value must be true
   *             if this state is the initial state of the parent state.
   */

  case class state(parent: state = null, init: Boolean = false) {
    /**
     * The name of this state, computed from the class name. Might not be
     * a reliable computation, needs some testing for corner cases.
     */

    // val stateName: String = this.getClass.getSimpleName.stripSuffix("$")
    val stateName: String = this.getClass.getName.split("\\$").last

    /**
     * If this state is a parent (super) state of other inner states,
     * this variable denotes the initial inner state of those.
     */

    private var initialState: state = null

    /**
     * Variable containing the code to execute when entering the state.
     */

    private[hsm] var entryCode: Unit => Unit = skip

    /**
     * variable containing the code to execute when exiting the state.
     */

    private[hsm] var exitCode: Unit => Unit = skip

    /**
     * The transitions out of a state are represented as a partial function, mapping
     * an event to a target {{{(s, code)}}} consisting of the target state {{{s}}}
     * and the {{{code}}} to execute when firing that transition.
     */

    private[hsm] var transitions: Transitions = noTransitions

    if (parent != null && init) {
      parent.initialState = this
    }

    /**
     * The standard equals method comparing two states for equality, meaning having
     * the same name.
     *
     * @param obj the state to compare to.
     * @return true if {{{this}}} state has the same name as the other {{{obj}}} state.
     */

    override def equals(obj: scala.Any): Boolean = {
      if (obj.isInstanceOf[state]) {
        val otherState: state = obj.asInstanceOf[state]
        stateName == otherState.stateName
      } else
        false
    }

    /**
     * Returns for a given state either the state itself if it has no substates, or,
     * if it is a superstate, it returns the innermost initial state. Note that when an HSM
     * transitions to a superstate, it effectively enters the innermost initial state of that
     * superstate.
     *
     * @return the innermost initial state.
     */

    private[hsm] def getInnerMostState: state =
      if (initialState == null) this else
        initialState.getInnerMostState

    /**
     * Returns a list of the superstates of a state, ordered top down. So if }}}this}}}
     * state has a superstate {{{A}}}, and {{{A}}} has a superstate {{{B}}},
     * and {{{B}}} has no superstate, then the list returned is: {{{List(B,A,this)}}}.
     *
     * @return list of superstates of the state, ordered top down.
     */

    private[hsm] def getSuperStates: List[state] =
      (if (parent == null) Nil else parent.getSuperStates) ++ List(this)

    /**
     * Registers code to be executed when entering the state.
     *
     * @param code the code to be executed when entering the state.
     */

    protected def entry(code: => Unit): Unit = {
      entryCode = (x: Unit) => code
    }

    /**
     * Registers code to be executed when exiting the state.
     *
     * @param code the code to be executed when exiting the state.
     */

    protected def exit(code: => Unit): Unit = {
      exitCode = (x: Unit) => code
    }

    /**
     * Used to define the transitions out of a state. The transitions are modeled as
     * a single partial function taking an event as argument, and returning a target, a pair
     * {{{(s,code)}}} consisting of the target state {{{s}}} and the
     * {{{code}}} to execute when firing that transition. The transition function
     * is only defined for those events for which it can fire, hence a partial function.
     *
     * @param ts the transitions out of the state represented as a partial function.
     */

    protected def when(ts: Transitions): Unit = {
      transitions = ts
    }

    /**
     * The standard toString method overriden to produce information including:
     * name of state, name of any superstate, and name of any initial substate.
     *
     * @return the string representation of the state.
     */

    override def toString: String = {
      var result = s"$stateName"
      if (parent != null) {
        if (init) result += " initial"
        result += s" parent=${parent.stateName}"
      }
      if (initialState != null) {
        result += s" initialChild=${initialState.stateName}"
      }
      result
    }
  }

  /**
   * The {{{stay}}} state is a special state. When used as target state in
   * a transition, it represents a self-loop where the exit and enter codes are NOT
   * executed. We just stay silently in the state. Otherwise, if in a state {{{S}}}
   * and a transition leads to the same state as target, {{{S}}}, the state's exit and enter
   * codes will be executed.
   */

  object stay extends state()
}


