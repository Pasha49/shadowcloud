package com.karasiq.shadowcloud.api

import scala.concurrent.Future

import akka.Done

import com.karasiq.shadowcloud.config.SerializedProps
import com.karasiq.shadowcloud.metadata.Metadata
import com.karasiq.shadowcloud.model._
import com.karasiq.shadowcloud.model.utils._

trait ShadowCloudApi {
  // -----------------------------------------------------------------------
  // Regions
  // -----------------------------------------------------------------------
  def getRegions(): Future[RegionStateReport]
  def getRegion(regionId: RegionId): Future[RegionStateReport.RegionStatus]
  def getStorage(storageId: StorageId): Future[RegionStateReport.StorageStatus]
  def addRegion(regionId: RegionId, regionConfig: SerializedProps): Future[RegionStateReport.RegionStatus]
  def addStorage(storageId: StorageId, storageProps: SerializedProps): Future[RegionStateReport.StorageStatus]
  def suspendRegion(regionId: RegionId): Future[Done]
  def suspendStorage(storageId: StorageId): Future[Done]
  def resumeRegion(regionId: RegionId): Future[Done]
  def resumeStorage(storageId: StorageId): Future[Done]
  def registerStorage(regionId: RegionId, storageId: StorageId): Future[Done]
  def unregisterStorage(regionId: RegionId, storageId: StorageId): Future[Done]
  def deleteRegion(regionId: RegionId): Future[RegionStateReport.RegionStatus]
  def deleteStorage(storageId: StorageId): Future[RegionStateReport.StorageStatus]
  def synchronizeStorage(storageId: StorageId, regionId: RegionId): Future[SyncReport]
  def synchronizeRegion(regionId: RegionId): Future[Map[StorageId, SyncReport]]
  def collectGarbage(regionId: RegionId, delete: Boolean = false): Future[GCReport]
  def compactIndex(storageId: StorageId, regionId: RegionId): Future[SyncReport]
  def compactIndexes(regionId: RegionId): Future[Map[StorageId, SyncReport]]

  // -----------------------------------------------------------------------
  // Folders
  // -----------------------------------------------------------------------
  def getFolder(regionId: RegionId, path: Path, dropChunks: Boolean = true, scope: IndexScope = IndexScope.default): Future[Folder]
  def createFolder(regionId: RegionId, path: Path): Future[Folder]
  def deleteFolder(regionId: RegionId, path: Path): Future[Folder]
  def copyFolder(regionId: RegionId, path: Path, newPath: Path, scope: IndexScope = IndexScope.default): Future[Folder]
  def mergeFolder(regionId: RegionId, folder: Folder): Future[Folder]

  // -----------------------------------------------------------------------
  // Files
  // -----------------------------------------------------------------------
  def getFiles(regionId: RegionId, path: Path, dropChunks: Boolean = true, scope: IndexScope = IndexScope.default): Future[Set[File]]
  def getFile(regionId: RegionId, path: Path, id: FileId, dropChunks: Boolean = true, scope: IndexScope = IndexScope.default): Future[File]
  def getFileAvailability(regionId: RegionId, file: File, scope: IndexScope = IndexScope.default): Future[FileAvailability]
  def getFileMetadata(regionId: RegionId, fileId: FileId, disposition: Metadata.Tag.Disposition): Future[Seq[Metadata]]
  def copyFiles(regionId: RegionId, path: Path, newPath: Path, scope: IndexScope = IndexScope.default): Future[Done]
  def copyFile(regionId: RegionId, file: File, newPath: Path, scope: IndexScope = IndexScope.default): Future[Done]
  def createFile(regionId: RegionId, file: File): Future[Done]
  def deleteFiles(regionId: RegionId, path: Path): Future[Set[File]]
  def deleteFile(regionId: RegionId, file: File): Future[File]
}
