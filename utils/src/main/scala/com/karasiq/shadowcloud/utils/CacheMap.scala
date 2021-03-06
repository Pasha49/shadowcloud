package com.karasiq.shadowcloud.utils

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object CacheMap {
  def apply[K, V](implicit ec: ExecutionContext): CacheMap[K, V] = {
    new CacheMap()
  }
}

class CacheMap[K, V](implicit ec: ExecutionContext) {
  private[this] val underlying = TrieMap.empty[K, Future[V]]

  def apply(key: K, cached: Boolean = true)(f: ⇒ Future[V]): Future[V] = {
    def executeRequest() = {
      val future = f
      future.onComplete(_.failed.foreach(_ ⇒ underlying.remove(key, future)))
      if (!cached) this -= key
      underlying.getOrElseUpdate(key, future)
    }

    if (cached) {
      underlying.getOrElse(key, executeRequest())
    } else {
      executeRequest()
    }
  }

  def -=(key: K): Unit = {
    underlying -= key
  }

  def clear(): Unit = {
    underlying.clear()
  }
}
