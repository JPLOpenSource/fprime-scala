package rules

import util.control.Breaks._

case class Rule(
                 val name: String,
                 val cond: Unit => Boolean,
                 val action: Unit => Unit,
                 var post: Option[Unit => Unit] = None) {

  def post(prop: => Boolean): Rule = {
    post = Some(u => assert(prop, s"*** violation of rule: $name"))
    this
  }

  def exec(): Unit = {
    println(s"executing rule $name")
    action()
    post match {
      case None =>
      case Some(prop) => prop()
    }
  }
}

trait Alg

case class AlgRepeatRandom(rules: List[Rule]) extends Alg

case class AlgRepeatRandomBounded(max: Int, rules: List[Rule]) extends Alg

case class AlgSeqAll(rules: List[Rule]) extends Alg

case class AlgSeqEnabled(rules: List[Rule]) extends Alg

case class AlgSeqFirst(rules: List[Rule]) extends Alg

case class AlgSeqUntil(rules: List[Rule]) extends Alg

case class AlgSeqAlg(algs: List[Alg]) extends Alg

case class AlgIf(cond: Unit => Boolean, th: Alg, el: Alg) extends Alg

case class AlgWhile(cond: Unit => Boolean, alg: Alg) extends Alg

case class AlgBounded(max: Int, alg: Alg) extends Alg

trait RuleDSL {
  var storedRules: List[Rule] = Nil

  var strategy: Option[Alg] = None

  val random = scala.util.Random

  def strategy(alg: Alg): Unit = {
    strategy = Some(alg)
  }

  def rule(name: String)(cond: => Boolean) = new {
    def ->(action: => Unit) = {
      println(s"created rule $name")
      val newRule = Rule(name, Unit => cond, Unit => action)
      storedRules ++= List(newRule)
      newRule
    }
  }

  def pickRuleRandomly(rules: List[Rule]): Option[Rule] = {
    val enabled = rules filter (r => r.cond())
    val size = enabled.size
    if (size == 0) {
      None
    } else {
      val index = random.nextInt(size)
      val rule = enabled(index)
      println(s"${enabled.map(r => r.name).mkString("[", ",", "]")} -> ${rule.name}")
      Some(rule)
    }
  }

  def Random = AlgRepeatRandom(storedRules)

  def Random(max: Int) = AlgRepeatRandomBounded(max, storedRules)

  def Random(rules: Rule*) = AlgRepeatRandom(rules.toList)

  def Random(max: Int, rules: Rule*) = AlgRepeatRandomBounded(max, rules.toList)

  def All(rules: Rule*) = AlgSeqAll(rules.toList)

  def Enabled(rules: Rule*) = AlgSeqEnabled(rules.toList)

  def First(rules: Rule*) = AlgSeqFirst(rules.toList)

  def Until(rules: Rule*) = AlgSeqUntil(rules.toList)

  def Seq(algs: Alg*) = AlgSeqAlg(algs.toList)

  def If(cond: => Boolean, th: Alg, el: Alg) = AlgIf(Unit => cond, th, el)

  def While(cond: => Boolean, alg: Alg) = AlgWhile(Unit => cond, alg)

  def Bounded(max: Int, alg: Alg) = AlgBounded(max, alg)

  def interpret(alg: Alg): Unit = {
    alg match {
      case AlgRepeatRandom(rules) =>
        breakable {
          while (true) {
            pickRuleRandomly(rules) match {
              case None => break
              case Some(rule) => rule.exec()
            }
          }
        }
      case AlgRepeatRandomBounded(max, rules) =>
        breakable {
          for (_ <- 0 to max) {
            pickRuleRandomly(rules) match {
              case None => break
              case Some(rule) => rule.exec()
            }
          }
        }
      case AlgSeqAll(rules) =>
        for (rule <- rules) {
          assert(rule.cond(), s"*** pre condition violated of rule ${rule.name}")
          rule.exec()
        }
      case AlgSeqEnabled(rules) =>
        for (rule <- rules) {
          if (rule.cond()) rule.exec()
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
              rule.exec()
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
        for (_ <- 0 to max) {
          interpret(alg)
        }
    }
  }

  def fire(): Unit = {
    strategy match {
      case None => println("*** A strategy has not been provided!")
      case Some(alg) => interpret(alg)
    }
  }
}
