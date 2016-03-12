package Domain

import Cqrs.Aggregate._
import Cqrs.Database.EventData
import Cqrs._
import Domain.Counter.{CounterAggregate, Created, Decremented, Incremented}

import scala.collection.immutable.TreeMap


object Counter {

  sealed trait Event
  final case class Created(id: AggregateId, start: Int) extends Event
  case object Incremented extends Event
  case object Decremented extends Event

  sealed trait Command
  final case class Create(id: AggregateId, start: Int) extends Command
  case object Increment extends Command
  case object Decrement extends Command

  val flow = new EventFlow[Command, Event]
  import flow.DslV1._
  import flow.{Flow, FlowAggregate}

  private def counting(c: Int): Flow[Unit] = handler(
    when(Increment).emit(Incremented).switch(counting(c + 1)),
    when(Decrement).guard(_ => c > 0, "Counter cannot be decremented").emit(Decremented).switch(counting(c - 1))
  )

  private val fullAggregate: Flow[Unit] = handler(
    when[Create].emit[Created].switch(evt => counting(evt.start))
  )

  object CounterAggregate extends FlowAggregate {
    val tag = createTag("Counter")
    def aggregateLogic = fullAggregate
  }
}

object CounterProjection extends Projection[TreeMap[AggregateId, Int]] {
    def initialData = TreeMap.empty
    val listeningFor = List(CounterAggregate.tag)
    def accept[E](d: Data) = {
      case EventData(_, id, _, Created(_, start)) => d + (id -> start)
      case EventData(_, id, _, Incremented) => d + (id -> d.get(id).fold( 1)(_ + 1))
      case EventData(_, id, _, Decremented) => d + (id -> d.get(id).fold(-1)(_ - 1))
    }
}

