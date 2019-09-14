package rules

import util.control.Breaks._

/**
 * Rules options to be set by the user.
 */

object RulesOptions {
  /**
   * Debug flag. When set to true various debugging information will
   * be printed on standard out as an application executes. The default
   * value is false.
   */

  var DEBUG = false
}

/**
 * Utilities.
 */

object Util {
  /**
   * Prints a message on standard out if the `DEBUG` flag is set.
   * Used for debugging purposes.
   *
   * @param msg the message to be printed.
   */

  def debug(msg : => String): Unit = {
    if (RulesOptions.DEBUG) println(s"[rul] $msg")
  }
}

import Util._

/**
 * A rule consists of a condition, the pre-condition, an action and an optional predicate, the post-condition,
 * to be verified after the action has been executed. A rule can e.g be declared as follows:
 *
 * {{{
 * rule("r3") { x > 0 } -> { x -= 1 } post { x < 100 }
 * }}}
 *
 * which reads: if `x > 0` then subtract `1` from `x` and verify that `x < 100`.
 * If the post-condition fails, an error will be recorded.
 * The rule has the name `r3`.
 *
 * Note that references to rules can be used in defining a rule execution strategy,
 * so sometimes it is useful to assign these references to variables, as in:
 *
 * {{{
 * val r3 = rule("r3") { x > 0 } -> { x -= 1 } post { x < 100 }
 * }}}
 *
 * @param name   name of the rule.
 * @param cond   condition of the rule. The rule can fire when this condition is true.
 * @param action action to be executed if the condition is true and the rule is chosen for execution.
 * @param post   optional post-condition to be evaluated after the action has been executed.
 */

case class Rule(
                 val name: String,
                 val cond: Unit => Boolean,
                 val action: Unit => Unit,
                 var post: Option[Unit => Unit] = None) {

  /**
   * Adds a post-condition to a rule.
   *
   * @param prop post-condition to be added.
   * @return the rule itself.
   */

  def post(prop: => Boolean): Rule = {
    post = Some(u => assert(prop, s"*** violation of rule: $name"))
    this
  }

  /**
   * Executes the rules under the assumption that the pre-condition has evaluated
   * to true. In addition, the post-condition, if present, is evaluated.
   */

  def exec(): Unit = {
    debug(s"executing rule $name")
    action()
    post match {
      case None =>
      case Some(prop) => prop()
    }
  }
}

/**
 * The type of algorithms for executing a set if rules. We will refer to the
 * sub-classes of this trait as being executable, with the meaning that
 * their interpretation (by the `interpret` function) is an execution.
 */

trait Alg

/**
 * Executes repeatedly a randomly chosen enabled rule amongst `rules`, forever, or until
 * no rule applies.
 *
 * @param rules rules to execute.
 */

private case class AlgRepeatRandom(rules: List[Rule]) extends Alg

/**
 * Executes repeatedly a randomly chosen enabled rule amongst `rules`, `max` times, or until
 * no rule applies.
 *
 * @param max   number of times a rule should be randomly picked and executed.
 * @param rules rules to execute.
 */

private case class AlgRepeatRandomBounded(max: Int, rules: List[Rule]) extends Alg

/**
 * Executes the rules in sequence, left to right. An error is recorded if the pre-condition
 * of a rule fails. That is: all rules have to execute.
 *
 * @param rules rules to execute.
 */

private case class AlgSeqAll(rules: List[Rule]) extends Alg

/**
 * Executes enabled rules in sequence, left to right. If a pre-condition of a rule
 * is not satisfied, the rule is just skipped, no error is recorded.
 *
 * @param rules rules to execute.
 */

private case class AlgSeqEnabled(rules: List[Rule]) extends Alg

/**
 * Executes a randomly chosen enabled rule once.
 *
 * @param rules rules to execute.
 */

private case class AlgSeqPickRandom(rules: List[Rule]) extends Alg

/**
 * Executes the first rule, from left, where the pre-condition evaluates to true.
 *
 * @param rules rules to execute.
 */

private case class AlgSeqFirst(rules: List[Rule]) extends Alg

/**
 * Executes the rules, from left to right, until a rule is reached where the pre-condition
 * is false. That and all remaining rules are skipped.
 *
 * @param rules rules to execute.
 */

private case class AlgSeqUntil(rules: List[Rule]) extends Alg

/**
 * Executes the sequence of algorithms, in order left to right.
 *
 * @param algs the algorithms to be executed.
 */

private case class AlgSeqAlg(algs: List[Alg]) extends Alg

/**
 * Represents an `if-then-else`: if the condition `cond` is true, then `th` is
 * executed, otherwise `el` is executed.
 *
 * @param cond condition to be evaluated.
 * @param th   executed if `cond` is true.
 * @param el   executed if `cond` is false.
 */

private case class AlgIf(cond: Unit => Boolean, th: Alg, el: Alg) extends Alg

/**
 * Represents a `while`: while the condition `cond` is true, execute `alg`.
 *
 * @param cond condition to be evaluated.
 * @param alg  algorithm to be executed repeated as long as `cond` evaluates to true.
 */

private case class AlgWhile(cond: Unit => Boolean, alg: Alg) extends Alg

/**
 * Executes the algorithm `alg` `max` times.
 *
 * @param max number of times to execute `alg`.
 * @param alg algorithm to be executed `max` times.
 */

private case class AlgBounded(max: Int, alg: Alg) extends Alg

/**
 * Trait providing the features for the rule-based DSL. A rule system must be
 * defined as a class subclassing this class, as in:
 *
 * {{{
 * class CountDown extends Rules {
 *   var x : Int = 11
 *
 *   rule ("count down") {x > 0} -> {
 *     x = x - 1
 *     println(x)
 *   }
 *
 *   strategy(Random)
 * }
 * }}}
 */

trait Rules {
  /**
   * The rules as declared in the class. Declaring a rule has the side-effect of
   * storing the rule in this variable, in the order declared.
   */

  private var storedRules: List[Rule] = Nil

  /**
   * The strategy according to which rules should be executed. The strategy is an
   * algorithm in the algorithm language represented by the trait `Alg`. A strategy
   * must to declared by a call of the `strategy(alg: Alg): Unit` function.
   */

  private var strategy: Option[Alg] = None

  /**
   * This variable holds invariants that have been defined by the user with one of the
   * `invariant` methods. Invariants are Boolean valued functions that are
   * evaluated after each rule execution. An invariant can e.g. check the values of variables
   * declared local to the rule class. The violation of an
   * invariant is reported as an error.
   */

  private var invariants: List[(String, Unit => Boolean)] = Nil

  /**
   * Used for computing random numbers.
   */

  private val random = scala.util.Random

  /**
   * Picks a rule randomly amongst the enabled rules of `rules` (those for which
   * the pre-condition is true). If there are no enabled rules, `None` is returned.
   *
   * @param rules
   * @return either `None` if no rule is enabled, or `Some(rule)` with some randomly
   *         picked enabled `rule`.
   */

  private def pickRuleRandomly(rules: List[Rule]): Option[Rule] = {
    val enabled = rules filter (r => r.cond())
    val size = enabled.size
    if (size == 0) {
      None
    } else {
      val index = random.nextInt(size)
      val rule = enabled(index)
      // println(s"${enabled.map(r => r.name).mkString("[", ",", "]")} -> ${rule.name}")
      Some(rule)
    }
  }

  /**
   * Executes a rule. Executes the function `before(): Unit` before the rule and
   * the function `after(): Unit` after the rule. These functions by default have
   * empty bodies, but can be overridden by the user.
   *
   * @param rule the rule to be executed.
   */

  private def execute(rule: Rule): Unit = {
    beforeRule()
    rule.exec()
    afterRule()
  }

  /**
   * Interprets an algorithm.
   *
   * @param alg the algorithm to be interpreted.
   */

  private def interpret(alg: Alg): Unit = {
    alg match {
      case AlgRepeatRandom(rules) =>
        breakable {
          while (true) {
            pickRuleRandomly(rules) match {
              case None => break
              case Some(rule) => execute(rule)
            }
          }
        }
      case AlgRepeatRandomBounded(max, rules) =>
        breakable {
          for (i <- 0 until max) {
            pickRuleRandomly(rules) match {
              case None => break
              case Some(rule) =>
                execute(rule)
            }
          }
        }
      case AlgSeqAll(rules) =>
        for (rule <- rules) {
          assert(rule.cond(), s"*** pre condition violated of rule ${rule.name}")
          execute(rule)
        }
      case AlgSeqEnabled(rules) =>
        for (rule <- rules) {
          if (rule.cond()) execute(rule)
        }
      case AlgSeqPickRandom(rules) =>
        pickRuleRandomly(rules) match {
          case None =>
          case Some(rule) => execute(rule)
        }
      case AlgSeqFirst(rules) =>
        rules.find(r => r.cond()) match {
          case None =>
          case Some(r) => r.exec()
        }
      case AlgSeqUntil(rules) =>
        breakable {
          for (rule <- rules) {
            if (rule.cond()) {
              execute(rule)
            } else {
              break
            }
          }
        }
      case AlgSeqAlg(algs) =>
        for (alg <- algs) {
          interpret(alg)
        }
      case AlgIf(cond, th, el) =>
        if (cond()) {
          interpret(th)
        } else {
          interpret(el)
        }
      case AlgWhile(cond, alg) =>
        while (cond()) {
          interpret(alg)
        }
      case AlgBounded(max, alg) =>
        for (_ <- 0 until max) {
          interpret(alg)
        }
    }
  }

  /**
   * A call of this function sets the rule execution strategy to be
   * the algorithm `alg`.
   *
   * @param alg the rule execution strategy.
   */

  protected def strategy(alg: Alg): Unit = {
    strategy = Some(alg)
  }

  /**
   * Executes the strategy (algorithm) declared with the `strategy(alg: Alg): Unit` function.
   * If none has been declared, `Random()` is used, which means repeated random execution of
   * enabled rules.
   */

  def fire(): Unit = {
    val algorithm : Alg  = strategy.getOrElse(Random())
    interpret(algorithm)
  }

  /**
   * Invariant method which takes an invariant Boolean valued expression (call by name)
   * as argument and adds the corresponding lambda abstraction (argument of type `Unit`)
   * to the list of invariants to check in the initial state and after each rule execution.
   *
   * @param inv the invariant expression to be checked in the initial state and
   *            after each rule execution.
   */

  protected def invariant(inv: => Boolean): Unit = {
    invariants ::= ("", ((x: Unit) => inv))
    assert(inv, s"*** violation invariant")
  }

  /**
   * Invariant method which takes an invariant Boolean valued expression (call by name)
   * as argument and adds the corresponding lambda abstraction (argument of type `Unit`)
   * to the list of invariants to check in the initial state and after each rule execution.
   * The first argument is a message that will be printed in case the invariant is violated.
   *
   * @param msg   message to be printed in case of an invariant violation.
   * @param inv the invariant expression to be checked in the initial state and
   *            after each rule execution.
   */

  protected def invariant(msg: String)(inv: => Boolean): Unit = {
    invariants ::= (msg, ((x: Unit) => inv))
    assert(inv, s"*** violation of invariant: $msg")
  }

  /**
   * Called before each rule execution. Can be overridden by user. By default
   * the function has an empty body and is equivalent to an empty statement.
   */

  protected def beforeRule() {}

  /**
   * Called after each rule execution. Can be overridden by user. By default
   * the function has an empty body and is equivalent to an empty statement.
   */

  protected def afterRule() {}

  /**
   * "Syntax" for declaring a rule. A rule declaration has one of two forms.
   * The first one is the basic form with a pre-condition and an action (but
   * no post-condition):
   *
   * {{{
   *   rule(name) {pre-condition} -> {action}
   * }}}
   *
   * The second form includes also the post-condition:
   *
   * {{{
   *   rule(name) {pre-condition} -> {action} post {post-condition}
   * }}}
   *
   * The return value of such a rule declaration is a reference to the rule, which
   * in addition, as a side-effect, has been stored in the list of declared rules.
   *
   * @param name the name of the rule.
   * @param cond the pre-condition of the rule.
   * @return an object that provides the "syntax" for defining the `-> {action}` part.
   */

  protected def rule(name: String)(cond: => Boolean) = new {
    def ->(action: => Unit) = {
      val newRule = Rule(name, Unit => cond, Unit => action)
      storedRules ++= List(newRule)
      newRule
    }
  }

  /**
   * Returns `storedRules` in case the argument rule list is empty. Used for defining
   * DSL functions where providing an empty list of rules means: use the declared
   * (stored) rules.
   *
   * @param rules the rules.
   * @return `rules` if not empty, otherwise `storedRules`.
   */

  private def storedIfEmpty(rules: List[Rule]): List[Rule] =
    if (rules.isEmpty) storedRules else rules

  // Functions for creating algorithms.

  /**
   * Returns an algorithm that executes repeatedly a randomly chosen enabled rule amongst
   * `rules`, forever, or until no rule applies.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param rules the rules to be executed.
   * @return the algorithm.
   */

  protected def Random(rules: Rule*): Alg =
    AlgRepeatRandom(storedIfEmpty(rules.toList))

  /**
   * Returns an algorithm that executes repeatedly a randomly chosen enabled rule amongst `rules`,
   * `max` times, or until no rule applies.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param max   number of times a rule should be randomly picked and executed.
   * @param rules the rules to be executed.
   * @return the algorithm.
   **/

  protected def Random(max: Int, rules: Rule*): Alg =
    AlgRepeatRandomBounded(max, storedIfEmpty(rules.toList))

  /**
   * Returns an algorithm that executes the rules in sequence, left to right. An error is recorded if
   * the pre-condition of a rule fails. That is: all rules have to execute.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param rules the rules to be executed.
   * @return the algorithm.
   */

  protected def All(rules: Rule*): Alg =
    AlgSeqAll(storedIfEmpty(rules.toList))

  /**
   * Returns an algorithm that executes enabled rules in sequence, left to right. If a
   * pre-condition of a rule is not satisfied, the rule is just skipped, no error is recorded.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param rules the rules to be executed.
   * @return the algorithm.
   */

  protected def Enabled(rules: Rule*): Alg =
    AlgSeqEnabled(storedIfEmpty(rules.toList))

  /**
   * Returns an algorithm that executes the first rule, from left, where the pre-condition evaluates
   * to true.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param rules the rules to be executed.
   * @return the algorithm.
   */

  protected def First(rules: Rule*): Alg =
    AlgSeqFirst(storedIfEmpty(rules.toList))

  /**
   * Executes a randomly chosen enabled rule once.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param rules the rules to be executed.
   * @return the algorithm.
   */

  protected def Pick(rules: Rule*): Alg =
    AlgSeqPickRandom(storedIfEmpty(rules.toList))

  /**
   * Returns an algorithm that executes the rules, from left to right, until a rule is
   * reached where the pre-condition is false. That and all remaining rules are skipped.
   *
   * The declared rules are used in case no rules are provided.
   *
   * @param rules the rules to be executed.
   * @return the algorithm.
   */

  protected def Until(rules: Rule*): Alg =
    AlgSeqUntil(storedIfEmpty(rules.toList))

  /**
   * Returns an algorithm that executes the sequence of algorithms, in order left to right.
   *
   * @param algs the algorithms to be executed.
   * @return the algorithm.
   */

  protected def Seq(algs: Alg*): Alg = AlgSeqAlg(algs.toList)

  /**
   * Returns an algorithm that represents an `if-then-else`: if the condition `cond` is true, then `th` is
   * executed, otherwise `el` is executed.
   *
   * @param cond condition to be evaluated.
   * @param th   executed if `cond` is true.
   * @param el   executed if `cond` is false.
   * @return the algorithm.
   */

  protected def If(cond: => Boolean, th: Alg, el: Alg): Alg = AlgIf(Unit => cond, th, el)

  /**
   * Returns an algorithm that represents a `while`: while the condition `cond` is true, execute `alg`.
   *
   * @param cond condition to be evaluated.
   * @param alg  algorithm to be executed repeated as long as `cond` evaluates to true.
   * @return the algorithm.
   */

  protected def While(cond: => Boolean, alg: Alg): Alg = AlgWhile(Unit => cond, alg)

  /**
   * Returns an algorithm that executes the algorithm `alg` `max` times.
   *
   * @param max number of times to execute `alg`.
   * @param alg algorithm to be executed `max` times.
   * @return the algorithm.
   */

  protected def Bounded(max: Int, alg: Alg): Alg = AlgBounded(max, alg)

  /**
   * Lifts a rule `r` to the algorithm: `Enabled(r)`.
   *
   * @param r rule to be lifted.
   * @return resulting `Enabled(r)` algorithm.
   */

  implicit def rule2Alg(r: Rule): Alg = Enabled(r)
}
