package Cqrs

import Cqrs.Aggregate._
import Cqrs.Database.FoldableDatabase.EventDataConsumerQuery
import cats.data.{ Xor, XorT }
import cats.free.Free
import cats.free.Free.liftF
import cats.{ Monad, MonadError }

import scala.util.Try

object Database {

  sealed trait Error
  final case class ErrorDbFailure(message: String) extends Error
  final case class EventDecodingFailure(rawData: String) extends Error
  //TODO: add tag
  final case class ErrorUnexpectedVersion(id: AggregateId, message: String) extends Error

  final case class ReadAggregateEventsResponse[E](lastVersion: Int, events: List[E], endOfStream: Boolean)

  sealed trait EventDatabaseOp[E, A]
  final case class ReadAggregateEvents[E](tag: EventTag, id: AggregateId, fromVersion: Int) extends EventDatabaseOp[E, Error Xor ReadAggregateEventsResponse[E]]
  final case class AppendAggregateEvents[E](tag: EventTag, id: AggregateId, expectedVersion: Int, events: List[E]) extends EventDatabaseOp[E, Error Xor Unit]

  type EventDatabase[E, A] = Free[EventDatabaseOp[E, ?], A]
  type EventDatabaseWithAnyFailure[E, Err, A] = XorT[EventDatabase[E, ?], Err, A]
  type EventDatabaseWithFailure[E, A] = EventDatabaseWithAnyFailure[E, Error, A]

  def lift[E, A](a: EventDatabaseOp[E, Error Xor A]): EventDatabaseWithFailure[E, A] =
    XorT[EventDatabase[E, ?], Error, A](liftF[EventDatabaseOp[E, ?], Error Xor A](a))

  def readNewEvents[E](tag: EventTag, id: AggregateId, fromVersion: Int): EventDatabaseWithFailure[E, ReadAggregateEventsResponse[E]] =
    lift(ReadAggregateEvents[E](tag, id, fromVersion))

  def appendEvents[E](tag: EventTag, id: AggregateId, expectedVersion: Int, events: List[E]): EventDatabaseWithFailure[E, Unit] =
    lift(AppendAggregateEvents(tag, id, expectedVersion, events))

  implicit def eventDatabaseMonad[E]: Monad[EventDatabase[E, ?]] = Free.freeMonad[EventDatabaseOp[E, ?]]
  implicit def eventDatabaseWithFailureMonad[E]: MonadError[EventDatabaseWithAnyFailure[E, ?, ?], Error] = XorT.xorTMonadError[EventDatabase[E, ?], Error]

  /**
   * Database backend exposing the DB API.
   */
  trait Backend {

    /**
     * Run aggregate interpreter
     *
     * @param actions  aggregate execution program
     * @tparam E       Type of aggregate events
     * @tparam A       return type from the given `actions` program
     * @return         error on failure or the returned value from the aggregate execution program
     */
    def runDb[E: EventSerialisation, A](actions: EventDatabaseWithFailure[E, A]): Error Xor A

    def runAggregate[E: EventSerialisation, A](actions: DatabaseWithAggregateFailure[E, A]): Aggregate.Error Xor A = {
      runDb(actions.value) match {
        case Xor.Left(err) => Xor.left(DatabaseError(err))
        case Xor.Right(Xor.Left(err)) => Xor.left(err)
        case Xor.Right(Xor.Right(ret)) => Xor.right(ret)
      }
    }
  }

  trait FoldableDatabase {
    def consumeDbEvents[D](fromOperation: Long, initData: D, queries: List[EventDataConsumerQuery[D]]): Error Xor (Long, D)
  }

  trait EventSerialisation[E] {
    def encode(event: E): String
    def decode(rawData: String): Error Xor E
  }

  implicit def defaultEventSerialisation[E](implicit w: upickle.default.Writer[E], r: upickle.default.Reader[E]): EventSerialisation[E] = new EventSerialisation[E] {
    def encode(event: E): String = upickle.json.write(w.write(event))
    def decode(rawData: String): Error Xor E = Try(Xor.right(r.read(upickle.json.read(rawData)))).getOrElse(Xor.left(EventDecodingFailure(rawData)))
  }

  final case class EventData[E](tag: EventTag, id: AggregateId, version: Int, data: E)
//  final case class EventData2[E](tag: EventTagAux[E], id: AggregateId, version: Int, data: E)

  object FoldableDatabase {
      final case class RawEventData(tag: EventTag, id: AggregateId, version: Int, data: String)

      /**
       * Db fold operation that updates the given data according to passed event
       *
       * @tparam D
       */
      trait EventDataConsumer[D] {
        def apply(data: D, event: RawEventData): Error Xor D
      }

      def createEventDataConsumer[E, D](handler: (D, EventData[E]) => D)(implicit eventSerialiser: EventSerialisation[E]) =
        new EventDataConsumer[D] {
          def apply(data: D, event: RawEventData): Error Xor D = eventSerialiser.decode(event.data).map(e => handler(data, EventData(event.tag, event.id, event.version, e)))
        }

      /**
       * A query specifying both the tag to load aggregate events by and the fold operation
       *
       * @param tag       Aggregate tag to load events from
       * @param consumer  Db fold operation
       * @tparam D        Data type for the db fold
       */
      final case class EventDataConsumerQuery[D](tag: EventTag, consumer: EventDataConsumer[D])
  }
}

