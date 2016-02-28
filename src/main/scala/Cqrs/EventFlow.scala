package Cqrs

import Cqrs.Aggregate.ErrorCannotFindHandler
import cats._
import cats.data.Xor
import cats.free.Free
import cats.free.Free.liftF
import lib.CaseClassTransformer

import scala.reflect.ClassTag

class EventFlow[Cmd, Evt] {
  type CommandH = PartialFunction[Cmd, Aggregate.Error Xor List[Evt]]
  type EventH[A] = PartialFunction[Evt, A]

  sealed trait FlowF[+Next]
  case class SetCommandHandler[Next](cmdh: CommandH, next: Next) extends FlowF[Next]
  case class EventHandler[Next, A](evth: EventH[A], whenHandled: A => Next) extends FlowF[Next]

  implicit object FlowFunctor extends Functor[FlowF] {
    def map[A, B](fa: FlowF[A])(f: A => B): FlowF[B] = fa match {
      case ch: SetCommandHandler[A] => SetCommandHandler[B](ch.cmdh, f(ch.next))
      case eh: EventHandler[A, t] => EventHandler[B, t](eh.evth, f compose eh.whenHandled)
    }
  }

  type Flow[A] = Free[FlowF, A]

  /**
   * Promotes a command to a event. The input types need to be isomoprhic. In other words
   * have the same fields + types
   * @tparam C The command type, should be isomorphic to the event type
   * @tparam E The event type, should be isomorphic to the command type
   * @return A CommandH, which takes a Cmd and either returns a error or a list of events (just one in this case)
   */
  def promote[C <: Cmd: ClassTag, E <: Evt](implicit cct: CaseClassTransformer[C, E]): PartialFunction[Cmd, Aggregate.Error Xor List[Evt]] =
    Function.unlift[Cmd, Aggregate.Error Xor List[Evt]] {
      //this raises: isInstanceOf is disabled by wartremover, but it has false positives:
      //https://github.com/puffnfresh/wartremover/issues/152
      case c: C => Some(Xor.right(cct.transform(c)).map(List(_)))
      case _ => None
    }

  def handler(ch: CommandH): Flow[Unit] = liftF(SetCommandHandler(ch, ()))
  def waitFor[A](eh: EventH[A]): Flow[A] = liftF(EventHandler[A, A](eh, identity))
  def waitForAndSwitch[A](eh: EventH[Flow[A]]): Flow[A] = waitFor(eh).flatMap((cont: Flow[A]) => cont)
  def runForever(): Flow[Unit] = waitFor(PartialFunction.empty[Evt, Unit])

  case class EventStreamConsumer(cmdh: CommandH, evh: Evt => Option[EventStreamConsumer])

  def esRunnerCompiler[A](initCmdH: CommandH, esRunner: Flow[A]): Option[EventStreamConsumer] =
    esRunner.fold(
      _ => None,
      {
        case SetCommandHandler(cmdh, next) => esRunnerCompiler(cmdh, next)
        case EventHandler(evth, cont) =>
          lazy val self: EventStreamConsumer = EventStreamConsumer(
            initCmdH,
            (ev: Evt) => evth.lift(ev) map (res => esRunnerCompiler(initCmdH, cont(res))) getOrElse Some(self)
          )
          Some(self)
      }
    )

  type StateData = Option[EventStreamConsumer]
  type EAD[A] = Aggregate.AggregateDef[Evt, StateData, A]

  //TODO: rm list from flows

  trait FlowAggregate extends Aggregate[Evt, Cmd, StateData] {
    def aggregateLogic: Flow[Unit]
    def on = e => d => d flatMap (_.evh(e))
    def handle = c => d => d.foldLeft(None: Option[Aggregate.Error Xor List[Evt]])(
      (prev: Option[Aggregate.Error Xor List[Evt]], consumer) => prev match {
        case Some(_) => prev
        case None => consumer.cmdh.lift(c)
      }
    ).getOrElse {
      Xor.Left(ErrorCannotFindHandler)
    }
    def initData = esRunnerCompiler(PartialFunction.empty, aggregateLogic)
  }
}

