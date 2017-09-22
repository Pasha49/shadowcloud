package com.karasiq.shadowcloud.persistence.h2

import java.util.UUID

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try

import akka.Done
import akka.actor.ActorSystem
import akka.util.ByteString

import com.karasiq.shadowcloud.ShadowCloud
import com.karasiq.shadowcloud.model.keys.{KeyChain, KeyId, KeySet}
import com.karasiq.shadowcloud.persistence.utils.SCQuillEncoders
import com.karasiq.shadowcloud.providers.KeyProvider

final class H2KeyProvider(actorSystem: ActorSystem) extends KeyProvider {
  private[this] val h2 = H2DB(actorSystem)
  private[this] val sc = ShadowCloud(actorSystem)

  import h2.context
  import context._

  // -----------------------------------------------------------------------
  // Schema
  // -----------------------------------------------------------------------
  private[this] object schema extends SCQuillEncoders {
    case class DBKey(id: UUID, forEncryption: Boolean, forDecryption: Boolean, key: ByteString)

    //noinspection TypeAnnotation
    implicit val keySchemaMeta = schemaMeta[DBKey]("sc_keys", _.id → "key_id",
      _.forEncryption → "for_encryption", _.forDecryption → "for_decryption",
      _.key → "serialized_key")
  }

  import schema._

  //noinspection TypeAnnotation
  private[this] object queries {
    val keys = quote {
      query[DBKey]
    }

    def addKey(key: DBKey) = quote {
      keys.insert(lift(key))
    }

    def modifyKey(keyId: KeyId, forEncryption: Boolean, forDecryption: Boolean) = quote {
      keys
        .filter(_.id == lift(keyId))
        .update(_.forEncryption → lift(forEncryption), _.forDecryption → lift(forDecryption))
    }
  }

  // -----------------------------------------------------------------------
  // Conversions
  // -----------------------------------------------------------------------
  private[this] object conversions {
    def toDBKey(keySet: KeySet, forEncryption: Boolean, forDecryption: Boolean): DBKey = {
      DBKey(keySet.id, forEncryption, forDecryption, sc.serialization.toBytes(keySet))
    }

    def toKeySet(key: DBKey): KeySet = {
      sc.serialization.fromBytes[KeySet](key.key)
    }
  }

  // -----------------------------------------------------------------------
  // Key manager functions
  // -----------------------------------------------------------------------
  def addKeySet(keySet: KeySet, forEncryption: Boolean, forDecryption: Boolean): Future[KeySet] = {
    Future.fromTry(Try {
      context.run(queries.addKey(conversions.toDBKey(keySet, forEncryption, forDecryption)))
      keySet
    })
  }

  def modifyKeySet(keyId: KeyId, forEncryption: Boolean, forDecryption: Boolean) = {
    Future.fromTry(Try {
      context.run(queries.modifyKey(keyId, forEncryption, forDecryption))
      Done
    })
  }

  def getKeyChain(): Future[KeyChain] = {
    def readKey(bs: ByteString): KeySet = sc.serialization.fromBytes[KeySet](bs)
    Future.fromTry(Try {
      val keys = context.run(queries.keys)
      KeyChain(
        keys.filter(_.forEncryption).map(dk ⇒ readKey(dk.key)),
        keys.filter(_.forDecryption).map(dk ⇒ readKey(dk.key)),
        keys.filterNot(k ⇒ k.forEncryption || k.forDecryption).map(dk ⇒ readKey(dk.key))
      )
    })
  }
}
