package com.karasiq.shadowcloud.storage.wrappers

import akka.stream.scaladsl.{Sink, Source}
import com.karasiq.shadowcloud.storage.utils.StorageUtils
import com.karasiq.shadowcloud.storage.{CategorizedRepository, Repository, StorageIOResult}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

private[storage] final class SubRepositoriesWrapper[CatKey, Key](pathString: String,
                                                                 subRepositories: () ⇒ Map[CatKey, Repository[Key]])
                                                                (implicit ec: ExecutionContext)
  extends CategorizedRepository[CatKey, Key] {
  
  override def subRepository(key: CatKey): Repository[Key] = {
    val map = subRepositories()
    map(key)
  }

  def keys: Source[(CatKey, Key), Result] = {
    val keySources = subRepositories().map { case (catKey, repo) ⇒ repo.keys.map((catKey, _)) }
    val emptySrc = Source.empty[(CatKey, Key)]
      .mapMaterializedValue(_ ⇒ Future.successful[StorageIOResult](StorageIOResult.Success(pathString, 0)))
    keySources.foldLeft(emptySrc)((s1, s2) ⇒ s1.concatMat(s2)(StorageUtils.foldIOFutures(_, _)))
  }

  def read(key: (CatKey, Key)): Source[Data, Result] = {
    subRepository(key._1).read(key._2)
  }

  def write(key: (CatKey, Key)): Sink[Data, Result] = {
    subRepository(key._1).write(key._2)
  }

  def delete(key: (CatKey, Key)): Result = {
    subRepository(key._1).delete(key._2)
  }
}
