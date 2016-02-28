package Domain

import cats.syntax.flatMap._

import Cqrs.Aggregate._
import Cqrs._

object Counter {

  sealed trait Event
  final case class Created(id: AggregateId, start: Int) extends Event
  case object Incremented extends Event
  case object Decremented extends Event

  sealed trait Command
  final case class Create(id: AggregateId, start: Int) extends Command with InitialAggregateCommand
  case object Increment extends Command
  case object Decrement extends Command

  val flow = new EventFlow[Command, Event]
  import flow._

  private def countingLogic(c: Int): Flow[Unit] =
    handler {
      case Increment => emitEvent(Incremented)
      case Decrement => if (c > 0) emitEvent(Decremented)
                        else failCommand("Counter cannot be decremented")
    } >>
      waitFor {
        case Incremented => c + 1
        case Decremented => c - 1
      } >>=
      countingLogic

  private val fullAggregateLogic: Flow[Unit] =
    handler { case Create(id, start) => emitEvent(Created(id, start)) } >> waitFor { case Created(_, _) => () } >> countingLogic(0)


  object CounterAggregate extends FlowAggregate {
    def tag = Tag("Counter")
    def aggregateLogic = fullAggregateLogic
    def initCmd = Create
  }
}

import scala.collection.immutable.TreeMap

object CounterProjection {

  type Data = TreeMap[AggregateId, Int]

  def emptyCounterProjection = Projection.build("counters").
    addHandler(Counter.CounterAggregate.tag, (d: Data, e: Database.EventData[Counter.Event]) => {
      import Counter._
      e.data match {
        case Created(id, start) => d.updated(e.id, start)
        case Incremented => d.updated(e.id, d.get(e.id).fold(1)(_ + 1))
        case Decremented => d.updated(e.id, d.get(e.id).fold(-1)(_ - 1))
      }
    }).empty(TreeMap.empty)
}

