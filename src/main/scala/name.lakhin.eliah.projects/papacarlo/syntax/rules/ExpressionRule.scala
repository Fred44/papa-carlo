/*
   Copyright 2013 Ilya Lakhin (Илья Александрович Лахин)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package name.lakhin.eliah.projects
package papacarlo.syntax.rules

import name.lakhin.eliah.projects.papacarlo.syntax.{Node, Session, Rule}
import name.lakhin.eliah.projects.papacarlo.syntax.Result._


final case class ExpressionRule(tag: String, atom: Rule) extends Rule {
  final class ExpressionState(private[ExpressionRule] val session: Session) {
    private[ExpressionRule] var issues: Boolean = false

    def parseRight(rightBindingPower: Int = Int.MaxValue) =
      ExpressionRule.this.parse(this, rightBindingPower)

    def placeholder = {
      val place = session.reference(session
        .relativeIndexOf(session.state.virtualPosition))
      new Node(RecoveryRule.PlaceholderKind, place, place)
    }

    def currentTokenReference =
      session.reference(session.relativeIndexOf(session.state.virtualPosition))

    def consume(expectedToken: String) = {
      val currentPosition = session.state.virtualPosition

      val actualKind = session
        .tokens
        .lift(currentPosition)
        .map(_.kind)
        .getOrElse(TokenRule.EndOfFragmentKind)

      if (expectedToken == actualKind) {
        val reference = currentTokenReference
        session.state = session.state
          .copy(virtualPosition = currentPosition + 1)
        Some(reference)
      } else {
        issues = true
        session.state = session.state
          .issue(expectedToken + " expected, but " + actualKind  + " found")
        None
      }
    }
  }

  final class Parselet {
    private[ExpressionRule] var lbp: Int = 0
    private[ExpressionRule] var nud = Option.empty[ExpressionState => Node]
    private[ExpressionRule] var led =
      Option.empty[(ExpressionState, Node) => Node]

    def leftBindingPower(lbp: Int) = {
      this.lbp = lbp

      this
    }

    def nullDenotation(procedure: ExpressionState => Node) = {
      this.nud = Some(procedure)

      this
    }

    def leftDenotation(procedure: (ExpressionState, Node) => Node) = {
      this.led = Some(procedure)

      this
    }
  }

  private var parselets = Map.empty[String, Parselet]

  def apply(session: Session) = {
    val state = new ExpressionState(session)

    parse(state) match {
      case Some(result) =>
        session.state = session.state.copy(products = (tag, result) ::
          session.state.products)

        if (state.issues) Recoverable
        else Successful

      case None => Failed
    }
  }

  def parselet(operator: String) =
    parselets.getOrElse(operator, {
      val parselet = new Parselet

      parselets += operator -> parselet

      parselet
    })

  private def token(session: Session) =
    session
      .tokens
      .lift(session.state.virtualPosition)
      .map(_.kind)
      .getOrElse(TokenRule.EndOfFragmentKind)

  private def next(session: Session) {
    session.state = session.state.copy(virtualPosition =
      session.state.virtualPosition + 1)
  }

  private def operand(expression: ExpressionState) = {
    val initialState = expression.session.state

    if (atom(expression.session) != Successful) expression.issues = true

    val result = expression.session.state.products
      .headOption
      .filter(_._1 == "operand" && expression.session.state.products.length >
        initialState.products.length)
      .map(_._2)

    expression.session.state =
      if (result.isDefined)
        expression.session.state.copy(products = initialState.products)
      else
        initialState.copy(issues = expression.session.state.issues)

    result
  }

  private def parse(expression: ExpressionState,
                    rightBindingPower: Int = Int.MaxValue): Option[Node] = {
    var step = parselets.get(token(expression.session))
      .flatMap(parselet => parselet.nud.map(nud => {
        next(expression.session)
        (Some(nud(expression)), 0)
      }))
      .getOrElse((operand(expression), 0))

    while (step._1.isDefined && rightBindingPower > step._2)
      step = parselets.get(token(expression.session))
      .flatMap(parselet => parselet.led.map(led => {
        next(expression.session)
        (step._1.map(left => led(expression, left)), parselet.lbp)
      }))
      .getOrElse(step.copy(_2 = Int.MaxValue))

    step._1
  }
}
