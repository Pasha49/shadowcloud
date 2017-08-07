package com.karasiq.shadowcloud.storage.inmem

import java.io.IOException

import scala.collection.concurrent.{Map ⇒ CMap}
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString

import com.karasiq.shadowcloud.exceptions.StorageException
import com.karasiq.shadowcloud.storage.StorageIOResult
import com.karasiq.shadowcloud.storage.utils.StorageUtils
import com.karasiq.shadowcloud.utils.HexString

private[inmem] final class ConcurrentMapStreams[K, V](map: CMap[K, V], length: V ⇒ Int) {
  def keys: Source[K, Future[StorageIOResult]] = {
    val promise = Promise[StorageIOResult]
    Source.fromIterator(() ⇒ map.keysIterator)
      .alsoTo(Sink.onComplete {
        case Success(_) ⇒
          promise.success(StorageIOResult.Success(rootPathString, 0))

        case Failure(error) ⇒
          promise.success(StorageIOResult.Failure(rootPathString, StorageUtils.wrapException(rootPathString, error)))
      })
      .mapMaterializedValue(_ ⇒ promise.future)
  }

  def read(key: K): Source[V, Future[StorageIOResult]] = {
    val path = toPathString(key)
    map.get(key) match {
      case Some(data) ⇒
        Source.single(data)
          .mapMaterializedValue(_ ⇒ Future.successful(StorageIOResult.Success(path, length(data))))

      case None ⇒
        Source.failed(StorageException.NotFound(path))
          .mapMaterializedValue(_ ⇒ Future.successful(StorageIOResult.Failure(path, StorageException.NotFound(path))))
    }
  }

  def write(key: K): Sink[V, Future[StorageIOResult]] = {
    val path = toPathString(key)
    if (map.contains(key)) {
      Sink.cancelled.mapMaterializedValue(_ ⇒ {
        Future.successful(StorageIOResult.Failure(path, StorageException.AlreadyExists(path)))
      })
    } else {
      val result = Promise[StorageIOResult]
      Flow[V]
        .limit(1)
        .map(data ⇒ (map.putIfAbsent(key, data), data))
        .alsoTo(Sink.foreach { case (oldValue, data) ⇒
          if (oldValue.isEmpty) {
            result.success(StorageIOResult.Success(path, length(data)))
          } else {
            result.success(StorageIOResult.Failure(path, StorageException.AlreadyExists(path)))
          }
        })
        .to(Sink.onComplete { _ ⇒
          result.trySuccess(StorageIOResult.Failure(path, StorageException.IOFailure(path, new IOException("No data written"))))
        })
        .mapMaterializedValue(_ ⇒ result.future)
    }
  }

  def delete: Sink[K, Future[StorageIOResult]] = {
    Flow[K]
      .map { key ⇒
        val path = key.toString
        map.remove(key)
          .map(deleted ⇒ StorageIOResult.Success(path, length(deleted)): StorageIOResult)
          .getOrElse(StorageIOResult.Failure(path, StorageException.NotFound(path)))
      }
      .fold(Seq.empty[StorageIOResult])(_ :+ _)
      .map(results ⇒ StorageUtils.foldIOResultsIgnoreErrors(results:_*))
      .toMat(Sink.head)(Keep.right)
  }

  private[this] def rootPathString: String = {
    "ConcurrentMap"
  }

  private[this] def toPathString(key: K): String = {
    val keyString = key match {
      case (region: String, hash: ByteString) ⇒
        region + "/" + HexString.encode(hash)

      case _ ⇒
        key.toString
    }
    keyString + " in " + rootPathString
  }
}
