package com.karasiq.shadowcloud.storage.utils

import scala.collection.SortedMap
import scala.language.postfixOps

import com.karasiq.shadowcloud.index.{ChunkIndex, FolderIndex}
import com.karasiq.shadowcloud.index.diffs.IndexDiff
import com.karasiq.shadowcloud.index.utils.HasEmpty
import com.karasiq.shadowcloud.model.{SequenceNr, StorageId}
import com.karasiq.shadowcloud.storage.utils.internal.DefaultIndexMerger

// TODO: UUID, time query
private[shadowcloud] trait IndexMerger[T] extends HasEmpty {
  def lastSequenceNr: T
  def chunks: ChunkIndex
  def folders: FolderIndex
  def diffs: SortedMap[T, IndexDiff]
  def mergedDiff: IndexDiff
  def pending: IndexDiff
  def add(sequenceNr: T, diff: IndexDiff): Unit
  def delete(sequenceNrs: Set[T]): Unit
  def addPending(diff: IndexDiff): Unit
  def deletePending(diff: IndexDiff): Unit
  def load(state: IndexMerger.State[T]): Unit
  def clear(): Unit

  override def isEmpty: Boolean = diffs.isEmpty
}

private[shadowcloud] object IndexMerger {
  final case class RegionKey(timestamp: Long = 0L, storageId: StorageId = "", sequenceNr: SequenceNr = 0L) {
    override def toString: String = {
      s"($storageId/$sequenceNr at $timestamp)"
    }

    override def hashCode(): Int = {
      (storageId, sequenceNr).hashCode()
    }

    override def equals(obj: scala.Any): Boolean = obj match {
      case RegionKey(_, `storageId`, `sequenceNr`) ⇒
        true

      case _ ⇒
        false
    }
  }

  object RegionKey {
    implicit val ordering: Ordering[RegionKey] = Ordering.by(key ⇒ (key.timestamp, key.storageId, key.sequenceNr))
    val zero: RegionKey = RegionKey()
  }

  final case class State[@specialized(Long) T](diffs: Seq[(T, IndexDiff)], pending: IndexDiff)

  def state[T](index: IndexMerger[T]): State[T] = {
    State(index.diffs.toVector, index.pending)
  }

  def restore[T: Ordering](zeroKey: T, state: State[T]): IndexMerger[T] = {
    val index = create(zeroKey)
    index.load(state)
    index
  }

  def create[T: Ordering](zeroKey: T): IndexMerger[T] = {
    new DefaultIndexMerger[T](zeroKey)
  }

  /**
    * Sequential index merger
    */
  def apply(): IndexMerger[SequenceNr] = {
    new DefaultIndexMerger[SequenceNr](SequenceNr.zero)
  }

  /**
    * Multi-sequence index merger
    */
  def region: IndexMerger[RegionKey] = {
    create(RegionKey.zero)(RegionKey.ordering)
  }
}