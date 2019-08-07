package hsm

/**
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
   * The execution trace of an HSM. Updated if <code>TRACE</code> is true.
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
   * Resets the <code>trace</code> variable.
   */

  def resetTrace(): Unit = {
    trace = ""
  }

  /**
   * Prints the <code>trace</code> variable.
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
 * provides all the internal DSL methods for defining HSMs.
 *
 * @tparam Event type of events communicated to the state machine. They must all be of this type,
 *               usually defined as case classes or case objects subclassing this type. However,
 *               it can be instantiated with any type.
 */

trait HSM[Event] {
  val hsmName : String = this.getClass.getSimpleName
  var delay = 0
  var isSystemHSM : Boolean = false

  class Cache[A, B](calculateIt: A => B) {
    var map: Map[A, B] = Map()

    def get(a: A): B = {
      var b: B = map.getOrElse(a, null.asInstanceOf[B])
      if (b == null) {
        b = calculateIt(a)
        map += (a -> b)
      }
      return b
    }
  }

  type Code = Unit => Unit
  type Target = (state, Code)
  type Transitions = PartialFunction[Event, Target]

  val noTransitions: Transitions = {
    case _ if false => null
  }
  val skip: Code = (x: Unit) => {}
  var exitEnterCache = new Cache[(state, state), (List[state], List[state])](getExitEnterStates)
  var initialCache = new Cache[state, state](getInitialState)
  var announceStateEntry: String => Unit = { case name => }
  var announceStateExit: String => Unit = { case name => }
  var announceQuiescent: Unit => Unit = { case _ => }
  var PRINT = false
  var PRINT_QUEUE = false

  def recordTransition(exitStates: List[state], event: Event, enterStates: List[state]): Unit = {
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

  def setAnnounceStateEntry(callback: String => Unit): Unit = { announceStateEntry = callback }
  def setAnnounceStateExit(callback: String => Unit): Unit = { announceStateExit = callback }

  def setQuiescent(callback: Unit => Unit): Unit = { announceQuiescent = callback }

  implicit def state2Target(s: state): Target =
    (s, skip)

  implicit def state2Exec(s: state) = new {
    def taking(d: Int):state = { delay += d ; s }
    def exec(code: => Unit) = (s, (x: Unit) => code)
  }

  var current: state = null

  def inState(regexp: String): Boolean  = {
    // TODO: optimize
    current.getSuperStates.exists(_.stateName.matches(regexp))
  }

  def inThisState(name: String): Boolean = {
    current.stateName == name
  }

  def printHeader(): Unit = {
    println(
      """
        |   _____           _       ______ __  __
        |  / ____|         | |     |  ____|  \/  |
        | | (___   ___ __ _| | __ _| |__  | \  / |
        |  \___ \ / __/ _` | |/ _` |  __| | |\/| |
        |  ____) | (_| (_| | | (_| | |    | |  | |
        | |_____/ \___\__,_|_|\__,_|_|    |_|  |_|
        |
        |
        |
      """.stripMargin
    )
  }

  if (PRINT) { printHeader() }

  def initial(s: state): Unit = {
    current = s.getInnerMostState
    current.entryCode()
  }

  val init = true

  def states(ss: state*){}

  def apply(event: Event): Int = {
    submit(event)
  }

  def findTriggerHappyState(s: state, event: Event): Option[state] =
    if (s.transitions.isDefinedAt(event))
      Some(s)
    else if (s.parent == null)
      None
    else
      findTriggerHappyState(s.parent, event)

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

  def getExitEnterStates(fromTo: (state, state)): (List[state], List[state]) = {
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

  def getInitialState(s: state): state =
    s.getInnerMostState

  def stripCommonPrefix(ss1: List[state], ss2: List[state]): (List[state], List[state]) = {
    if (ss1 == Nil && ss2 == Nil)
      (Nil, Nil)
    else if (ss1.head.eq(ss2.head))
      stripCommonPrefix(ss1.tail, ss2.tail)
    else
      (ss1, ss2)
  }

  case class state(parent: state = null, init: Boolean = false) {
    // val stateName: String = this.getClass.getSimpleName.stripSuffix("$")
    val stateName: String = this.getClass.getName.split("\\$").last
    var initialState: state = null
    var entryCode: Unit => Unit = skip
    var exitCode: Unit => Unit = skip
    var transitions: Transitions = noTransitions

    if (parent != null && init) {
      parent.initialState = this
    }

    override def equals(obj: scala.Any): Boolean = {
      if (obj.isInstanceOf[state]) {
        val otherState: state = obj.asInstanceOf[state]
        stateName == otherState.stateName
      } else
        false
    }

    def getInnerMostState: state =
      if (initialState == null) this else
        initialState.getInnerMostState

    def getSuperStates: List[state] =
      (if (parent == null) Nil else parent.getSuperStates) ++ List(this)

    def entry(code: => Unit): Unit = {
      entryCode = (x: Unit) => code
    }

    def exit(code: => Unit): Unit = {
      exitCode = (x: Unit) => code
    }

    def when(ts: Transitions): Unit = {
      transitions = ts
    }

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

  object stay extends state()
}


