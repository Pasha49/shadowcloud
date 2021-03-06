package com.karasiq.shadowcloud.streams.region

import scala.concurrent.ExecutionContext
import scala.language.postfixOps

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}

import com.karasiq.shadowcloud.config.{ParallelismConfig, TimeoutsConfig}
import com.karasiq.shadowcloud.exceptions.SCException
import com.karasiq.shadowcloud.index.files.FileVersions
import com.karasiq.shadowcloud.model.{Chunk, File, Path, RegionId}
import com.karasiq.shadowcloud.ops.region.RegionOps
import com.karasiq.shadowcloud.streams.file.FileIndexer

object RegionStreams {
  def apply(regionOps: RegionOps,
            parallelism: ParallelismConfig,
            timeouts: TimeoutsConfig)
           (implicit ec: ExecutionContext): RegionStreams = {
    new RegionStreams(regionOps, parallelism, timeouts)
  }
}

//noinspection TypeAnnotation
// RegionOps wrapped in flows
final class RegionStreams(regionOps: RegionOps,
                          parallelism: ParallelismConfig,
                          timeouts: TimeoutsConfig)
                         (implicit ec: ExecutionContext) {

  val writeChunks = Flow[(RegionId, Chunk)]
    .mapAsync(parallelism.write) { case (regionId, chunk) ⇒ regionOps.writeChunk(regionId, chunk) }
    .named("writeChunks")

  val readChunksEncrypted = Flow[(RegionId, Chunk)]
    .mapAsync(parallelism.read) { case (regionId, chunk) ⇒ regionOps.readChunkEncrypted(regionId, chunk) }
    .named("readChunks")

  val readChunks = Flow[(RegionId, Chunk)]
    .mapAsync(parallelism.read) { case (regionId, chunk) ⇒ regionOps.readChunk(regionId, chunk) }
    .named("readChunks")

  val findFiles = Flow[(RegionId, Path)]
    .mapAsync(parallelism.query) { case (regionId, path) ⇒
      regionOps.getFiles(regionId, path)
        .map((path, _))
        .recover { case error if SCException.isNotFound(error) ⇒ (path, Set.empty[File]) }
    }
    .named("findFiles")

  val findFile = findFiles
    .map(e ⇒ FileVersions.mostRecent(e._2))
    .named("findFile")

  val getFolder = Flow[(RegionId, Path)]
    .mapAsync(parallelism.query) { case (regionId, path) ⇒
      regionOps.getFolder(regionId, path)
    }
    .named("getFolder")

  val createFile: Flow[(RegionId, Path, FileIndexer.Result), File, NotUsed] = {
    Flow[(String, Path, FileIndexer.Result)]
      .flatMapConcat { case (regionId, path, result) ⇒
        val future = regionOps.createFile(regionId, File.create(path, result.checksum, result.chunks))
        Source.fromFuture(future)
      }
      .named("createFile")
  }
}
