package com.karasiq.shadowcloud.persistence.h2

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.persistence.journal.{AsyncWriteJournal, Tagged}
import akka.serialization.SerializationExtension
import akka.stream.ActorAttributes
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString

import com.karasiq.shadowcloud.persistence.utils.SCQuillEncoders

class H2AkkaJournal extends AsyncWriteJournal {
  // -----------------------------------------------------------------------
  // Context
  // -----------------------------------------------------------------------
  private[this] val h2DB = H2DB(context.system)
  private[this] val serializer = SerializationExtension(context.system).serializerFor(classOf[PersistentRepr])

  import context.dispatcher
  import h2DB.context.db
  import db._
  import h2DB.sc.implicits.materializer

  // -----------------------------------------------------------------------
  // Schema
  // -----------------------------------------------------------------------
  private[this] object schema extends SCQuillEncoders {
    case class JournalRow(ordering: Long, persistenceId: String, sequenceNr: Long, deleted: Boolean, tags: Set[String], message: ByteString)

    implicit val tagsEncoder: Encoder[Set[String]] = encoder(java.sql.Types.ARRAY, (index, value, row) ⇒
      row.setObject(index, value.toArray, java.sql.Types.ARRAY))

    implicit val tagsDecoder: Decoder[Set[String]] = decoder(java.sql.Types.ARRAY, (index, row) ⇒
      row.getArray(index).getArray().asInstanceOf[Array[java.lang.Object]].toSet.asInstanceOf[Set[String]]
    )

    implicit val journalRowSchemaMeta = schemaMeta[JournalRow]("sc_akka_journal")
  }

  import schema._

  //noinspection TypeAnnotation
  private[this] object queries {
    def write(messages: List[JournalRow]) = quote {
      liftQuery(messages)
        .foreach(jr ⇒ query[JournalRow].insert(jr).returning(_.ordering))
    }

    def markAsDeleted(persistenceId: String, toSequenceNr: Long) = quote {
      query[JournalRow]
        .filter(jr ⇒ jr.persistenceId == lift(persistenceId) && jr.sequenceNr <= lift(toSequenceNr))
        .update(_.deleted → true)
    }

    def lastForPersistenceId(persistenceId: String) = quote {
      query[JournalRow]
        .filter(jr ⇒ jr.persistenceId == lift(persistenceId) && !jr.deleted)
        .sortBy(_.sequenceNr)(Ord.desc)
    }

    def messagesForPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Int) = quote {
      query[JournalRow]
        .filter(jr ⇒ jr.persistenceId == lift(persistenceId) && jr.sequenceNr >= lift(fromSequenceNr) && jr.sequenceNr <= lift(toSequenceNr) && !jr.deleted)
        .sortBy(_.sequenceNr)(Ord.asc)
        .take(lift(max))
    }

    def highestSequenceNr(persistenceId: String) = quote {
      lastForPersistenceId(persistenceId)
        .map(_.sequenceNr)
        .take(1)
    }
  }

  // -----------------------------------------------------------------------
  // Write functions
  // -----------------------------------------------------------------------
  def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    def toJournalRow(pr: PersistentRepr, tags: Set[String]): JournalRow = {
      JournalRow(Long.MinValue, pr.persistenceId, pr.sequenceNr, pr.deleted, tags, ByteString(serializer.toBinary(pr)))
    }
    Source(messages)
      .map(_.payload.map(pr ⇒ pr.payload match {
        case Tagged(payload, tags) ⇒
          toJournalRow(pr.withPayload(payload), tags)

        case _ ⇒
          toJournalRow(pr, Set.empty)
      }))
      .map(rows ⇒ Try(db.run(queries.write(rows.toList))).map(_ ⇒ ()))
      .addAttributes(ActorAttributes.dispatcher("shadowcloud.persistence.h2.dispatcher"))
      .runWith(Sink.seq)
  }

  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    Future.fromTry(Try(db.run(queries.markAsDeleted(persistenceId, toSequenceNr))))
  }

  def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)
                         (recoveryCallback: (PersistentRepr) => Unit): Future[Unit] = {
    def toPersistentRepr(jr: JournalRow): PersistentRepr = {
      serializer.fromBinary(jr.message.toArray, classOf[PersistentRepr]).asInstanceOf[PersistentRepr]
    }
    Source
      .fromFuture(Future.fromTry(Try(db.run(queries.messagesForPersistenceId(persistenceId, fromSequenceNr, toSequenceNr, Math.min(max, Int.MaxValue).toInt)))))
      .mapConcat(identity)
      .map(toPersistentRepr)
      .runForeach(recoveryCallback)
      .map(_ ⇒ ())
  }

  def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    Future.fromTry(Try(db.run(queries.highestSequenceNr(persistenceId)).headOption.getOrElse(0)))
  }
}