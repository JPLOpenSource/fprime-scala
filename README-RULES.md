# Programming with Rules

Rules is an internal Scala DSL for rule-based programming in the style of [Guarded Command Language](https://en.wikipedia.org/wiki/Guarded_Command_Language). Rule programs may show non-deterministic behavior when in some program states more than one rule is enabled to execute, and the choise becomes random. Rule-based programs can therefore be useful for writing randomized tests. 

The Rules concept for testing was introduced and advocated by 
[Robert Bocchino](http://rob-bocchino.net/Professional/Home.html) at Jet Propulsion Laboratory, for testing F' programs written in C++. The same concept is here implemented in Scala for experimental purposes.

## The Basics

### Basic Format

A rule program is a set of rules. A rule consists of a pre-condition (a Boolean Scala expression), an action (an arbitrary  Scala statement), and an optional post-condition to be verified (if present) after the action has been executed. It has one of the the following two forms in the Rules DSL:

```scala
rule ("r1") (pre-condition) -> {action}
```

or

```scala
rule ("r1") (pre-condition) -> {action} post (post-condition)
```

A rule is _enabled_ in a state, if its pre-condition evaluates to true. An enabled rule can execute if chosen to execute. Executing a rule means executing the action. If a post-condition is provided, it is evaluated in the resulting state, and an error is issed if it evaluates to false.

### Example: Euclid's Algorithm

To illustrate an example, consider Euclid's algorithm for computing 
the greatest common divisor of two numbers `A` and `B`, as presented in [Guarded Command Language](https://en.wikipedia.org/wiki/Guarded_Command_Language):

```
a, b := A, B;
do
  a < b → b := b - a
| b < a → a := a - b
od
```

This algorithm can be encoded in Rules as follows.

```scala
import rules._

class Euclid(A: Int, B: Int) extends Rules {
  var a : Int = A
  var b : Int = B

  rule ("r1") (a < b) -> {b = b - a}
  rule ("r2") (b < a) -> {a = a - b}
}
```

The class `Euclid` (parameterized with the numbers `A` and `B`)
subclasses the class `Rules`, which defines the rule DSL. Two ("normal" Scala) variables are declared: `a` and `b`, and two rules are declared, named `r1` and `r2`.

Let's now try to apply our rule program. This is done in the following main program:

```scala
object Main1 {
  def main(args: Array[String]): Unit = {
    val rules = new Euclid(28,16)
    rules.fire()
    println(s"a = ${rules.a}, b = ${rules.b}")
  }
}
```

We simply make an instance of the `Euclid` class and call the  method `fire()` on it.

The rules will repeatedly fire until the rule program terminanes when none of the rules are enabled. This is the case when `a == b`. The above program prints out:

```
a = 4, b = 4
```
Note, that a rule system may not terminate as in this case.

## A Little Aspect-Oriented Programming

We saw above how we print out the values of the variables 
`a` and `b` after the call of `fire()`. We can instead
instruct the rule system to print out the values of `a` and `b`
after each rule execution. The `Rules` class (the DSL) offers two methods that can be overidden by the programmer, their bodies are initially empty.

```scala
def beforeRule() : Unit = {}
def afterRule() : Unit = {}
```

The function `beforeRule` is called by the rule system before the execution of each rule, and the function `afterRule` is called after the execution of each rule. In the following main program, we override the `afterRule` function to print the values of `a` and `b`:

```scala
object Main2 {
 def main(args: Array[String]): Unit = {
   val rules = new Euclid(28,16) {
     override def afterRule() {println(s"a = $a, b = $b")}
   }
   rules.fire()
 }
}
```

The output of executing this rule-based program is:

```
a = 12, b = 16
a = 12, b = 4
a = 8, b = 4
a = 4, b = 4
```

## Post Conditions and Invariants

### Post Conditons

As already mentioned, rules can be defined with a post-conditon, which
will get evaluated after each rule execution. In the Euclid example we might want to check that `a` and `b` always stay positive. Expressing this as post-conditions results in the following rules:

```scala
  rule ("r1") (a < b) -> {b = b - a} post (b > 0)
  rule ("r2") (b < a) -> {a = a - b} post (a > 0)
```

### Invariants

In this particular case, however, it may be more convenient to express
this with a class invariant, a Boolean expresssion that should be true
after _any_ rule execution. We can write the above example instad
as follows.

```scala
class Euclid(A: Int, B: Int) extends Rules {
  var a : Int = A
  var b : Int = B

  invariant {a > 0 && b > 0}

  rule ("r1") (a < b) -> {b = b - a}
  rule ("r2") (b < a) -> {a = a - b}
}
```

## Strategies

We have above shown an example (`Euclid`) where a call of the `fire()` method by default causes enabled rules to be repeatedly and randomly executed: in each step a random enabled rule is picked and executed,
forever, or until no rules are enabled. 

### Changing Execution Strategy

It is, however, possible to define different execution strategies. Defining a new execution strategy is done by calling the function:

```scala
def strategy(alg: Alg): Unit
```

It takes as argument a strategy _algorithm_ of type `Alg`. 

### Composing Strategy Algorithms

A collection of functions are provided for composing such algorithms. These are as follows:

```scala
def Random(rules: Rule*): Alg 

  // Returns an algorithm that executes repeatedly a randomly chosen   
  // enabled rule, forever, or until no rule applies.

def Random(max: Int, rules: Rule*): Alg 

  // Returns an algorithm that executes repeatedly a randomly chosen
  // enabled rule, `max` times, or until no rule applies.

def All(rules: Rule*): Alg 

   // Returns an algorithm that executes the rules in sequence, 
   // left to right. An error is recorded if the pre-condition 
   // of a rule fails. That is: all rules have to execute.

def Enabled(rules: Rule*): Alg 

   // Returns an algorithm that executes enabled rules in sequence, 
   // left to right. If a pre-condition of a rule is not satisfied, 
   // the rule is just skipped, no error is recorded.

def First(rules: Rule*): Alg 

   // Returns an algorithm that executes the first rule, from left, 
   // where the pre-condition evaluates to true.

def Pick(rules: Rule*): Alg 

   // Executes a randomly chosen enabled rule once.

def Until(rules: Rule*): Alg 

   // Returns an algorithm that executes the rules, from 
   // left to right, until a rule is reached where the 
   // pre-condition is false. That and all remaining rules 
   // are skipped.

def Seq(algs: Alg*): Alg 

   // Returns an algorithm that executes the sequence of algorithms, 
   // in order left to right.

def If(cond: => Boolean, th: Alg, el: Alg): Alg 

   // Returns an algorithm that represents an if-then-else: if the 
   // condition `cond` is true, then `th` is executed, otherwise 
   // `el` is executed.

def While(cond: => Boolean, alg: Alg): Alg 

   // Returns an algorithm that represents a while-loop: while the 
   // condition `cond` is true, execute `alg`.

def Bounded(max: Int, alg: Alg): Alg 

   // Returns an algorithm that executes the algorithm 
   `alg` max times.
```

The first seven functions take a var-arg list of rules as arguments. If no rules are provided, the rules decared by the user as shown above will be used. Rules can, however, be used, as for example here:

```scala
val r1 = rule ("r1") (a < b) -> {b = b - a}
val r2 = rule ("r2") (b < a) -> {a = a - b}

strategy(Random(10,r1,r2))
```

### An Example of a User Defined Strategy

Consider the modeling of a three dimensional cube of length 5 on each side. We want to model the movement of a regimented fly in this box,
only willing to move in one dimension at a time, one unit at a time.
We model the fly's position with the three coordinate variables `x`,
`y`, and `z`, and its movements with six rules, three for increasing
the three variables and three for decreasing them:

```scala
import rules._

class RuleSystem extends Rules {
  var x: Int = 0
  var y: Int = 0
  var z: Int = 0

  val xUp = rule("x up")(x < 5) -> {x += 1}
  val yUp = rule("y up")(y < 5) -> {y += 1}
  val zUp = rule("z up")(z < 5) -> {z += 1}

  val xDown = rule("x down")(x > 0) -> {x -= 1}
  val yDown = rule("y down")(y > 0) -> {y -= 1}
  val zDown = rule("z down")(z > 0) -> {z -= 1}

  override def afterRule(): Unit = {
    println(s"x = $x, y = $y, z = $z")
  }
}
```

We now write the main program, creating an instance of the rule system, and defining a special execution strategy:

```scala
object Main {
  def main(args: Array[String]): Unit = {
    val rules = new RuleSystem {
      strategy(
        Seq(
          Random(xUp, yUp),
          While(z < 3, zUp),
          All(xDown,yDown,zDown),
          Random(3)
        )
      )
    }
    rules.fire()
  }
}
```

The strategy programmed above is:

1. Randomly execute `xUp` and `yUp` until none of them are enabled.
2. While `z < 3` execute `zUp`.
3. Execute all the rules `xDown`, `yDown`, and `zDown`, once in that order, expecting them all to be enabled (fail if not).
4. Three times randomly execute enabled rules amongst all the declared six rules.

One output from this program is the following, annorated with
comments relating to the steps above:

```scala
// Step 1: Randomly execute `xUp` and `yUp` until none of them are enabled.

x = 0, y = 1, z = 0
x = 0, y = 2, z = 0
x = 0, y = 3, z = 0
x = 0, y = 4, z = 0
x = 1, y = 4, z = 0
x = 2, y = 4, z = 0
x = 2, y = 5, z = 0
x = 3, y = 5, z = 0
x = 4, y = 5, z = 0
x = 5, y = 5, z = 0

// Step 2: While `z < 3` execute `zUp`.

x = 5, y = 5, z = 1
x = 5, y = 5, z = 2
x = 5, y = 5, z = 3

// Step 3: Execute all the rules `xDown`, `yDown`, and `zDown`.

x = 4, y = 5, z = 3
x = 4, y = 4, z = 3
x = 4, y = 4, z = 2

// Step 4: Three times randomly execute enabled rules amongst all the declared six rules.

x = 4, y = 5, z = 2
x = 3, y = 5, z = 2
x = 2, y = 5, z = 2
```

## Options

Rules offers an option variable that can be set:

- `RulesOptions.DEBUG` (static variable): when set to true, causes rule execution steps to be printed. Default is false.

This option can be set as shown in the following example:

```scala
RulesOptions.DEBUG = true
val r = MyRules()
r.fire()
```
