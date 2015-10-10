package Cqrs

import Cqrs.Aggregate._
import InMemoryDb._

import scala.collection.immutable.TreeMap

object Projection {
  trait Handler[E, D] {
    def hashPrefix: String
    def handle(id: AggregateId, e: E, data: D): D
  }

  def applyNewEventsFromDbToProjection[E, D](db: DbBackend[E], initialProjection: Projection[D])(implicit handler: Handler[E, D]): Projection[D] = {
    val prefix = handler.hashPrefix + "_"
    println("====== vv =====.... " + handler.hashPrefix)

    def applyNewEventsToData(data: D, aggregateId: AggregateId, events: TreeMap[Int, List[E]]) = {
      events.foldLeft(data)((d, el) => el._2.foldLeft(d)((d_, event) => handler.handle(aggregateId, event, d_)))
    }

    def applyNewAggregateEvents(proj: Projection[D], aggregateId: AggregateId, events: TreeMap[Int, List[E]]) = {
      val aggregateHash = prefix + aggregateId
      val fromVersion = proj.readEvents.get(aggregateHash).fold(0)(_ + 1)
      val newEvents = events.from(fromVersion)
      val newData = applyNewEventsToData(proj.data, aggregateId, newEvents)
      val newReadEvents = proj.readEvents.updated(aggregateHash, newEvents.lastKey)
      Projection[D](newReadEvents, newData)
    }

    db.foldLeft(initialProjection)((proj, farg) => applyNewAggregateEvents(proj, farg._1, farg._2))
  }
}

final case class Projection[D](readEvents: TreeMap[String, Int], data: D) {

  import Projection._

  def applyNewEventsFromDb[E](db: DbBackend[E])(implicit handler: Handler[E, D]): Projection[D] = {
    val r = applyNewEventsFromDbToProjection(db, this)
    println(r)
    r
  }
}