package com.karasiq.shadowcloud.streams

import java.util.UUID

import scala.concurrent.Future

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Compression, Flow, Framing, GraphDSL, Keep, Source, Zip}
import akka.util.ByteString

import com.karasiq.shadowcloud.config.MetadataConfig
import com.karasiq.shadowcloud.index.{File, Folder, Path}
import com.karasiq.shadowcloud.metadata.Metadata
import Metadata.Tag.{Disposition ⇒ MDDisposition}
import com.karasiq.shadowcloud.providers.MetadataModuleRegistry
import com.karasiq.shadowcloud.serialization.{SerializationModule, StreamSerialization}
import com.karasiq.shadowcloud.streams.metadata.MimeDetectorStream
import com.karasiq.shadowcloud.streams.utils.ByteStringLimit
import com.karasiq.shadowcloud.utils.Utils

private[shadowcloud] object MetadataStreams {
  def apply(config: MetadataConfig, modules: MetadataModuleRegistry,
            fileStreams: FileStreams, regionOps: RegionOps,
            serialization: SerializationModule): MetadataStreams = {
    new MetadataStreams(config, modules, fileStreams, regionOps, serialization)
  }

  private val metadataFolderPath = Utils.internalFolderPath / "metadata"

  private def getFolderPath(fileId: File.ID): Path = {
    metadataFolderPath / fileId.toString.toLowerCase
  }

  private def getFilePath(fileId: File.ID, disposition: MDDisposition): Path = {
    getFolderPath(fileId) / disposition.toString().toLowerCase
  }

  private def getDisposition(tag: Option[Metadata.Tag]): MDDisposition = {
    tag.fold(MDDisposition.METADATA: MDDisposition)(_.disposition)
  }
}

private[shadowcloud] final class MetadataStreams(config: MetadataConfig,
                                                 modules: MetadataModuleRegistry,
                                                 fileStreams: FileStreams,
                                                 regionOps: RegionOps,
                                                 serialization: SerializationModule) {

  def keys(regionId: String): Source[File.ID, NotUsed] = {
    Source.fromFuture(regionOps.getFolder(regionId, MetadataStreams.metadataFolderPath))
      .recover { case _ ⇒ Folder(MetadataStreams.metadataFolderPath) }
      .mapConcat(_.folders.map(UUID.fromString))
  }
  
  def write(regionId: String, fileId: File.ID, disposition: MDDisposition): Flow[Metadata, File, NotUsed] = {
    def writeMetadataFile(regionId: String, path: Path) = {
      Flow[Metadata]
        .via(StreamSerialization(serialization).toBytes)
        .via(Framing.simpleFramingProtocolEncoder(config.metadataFrameLimit))
        .via(Compression.gzip)
        .via(fileStreams.write(regionId, path))
    }

    Flow[Metadata].via(writeMetadataFile(regionId, MetadataStreams.getFilePath(fileId, disposition)))
  }

  def write(regionId: String, fileId: File.ID): Flow[Metadata, File, NotUsed] = {
    Flow[Metadata]
      .groupBy(10, v ⇒ MetadataStreams.getDisposition(v.tag))
      .fold(Vector.empty[Metadata])(_ :+ _)
      .flatMapConcat { values ⇒
        val disposition = MetadataStreams.getDisposition(values.head.tag)
        Source(values).via(write(regionId, fileId, disposition))
      }
      .mergeSubstreams
  }

  def list(regionId: String, fileId: File.ID): Future[Folder] = {
    val path = MetadataStreams.getFolderPath(fileId)
    regionOps.getFolder(regionId, path)
  }

  def read(regionId: String, fileId: File.ID, disposition: MDDisposition): Source[Metadata, NotUsed] = {
    def readMetadataFile(regionId: String, fileId: File.ID, disposition: MDDisposition) = {
      fileStreams.readMostRecent(regionId, MetadataStreams.getFilePath(fileId, disposition))
        .via(Compression.gunzip())
        .via(Framing.simpleFramingProtocolDecoder(config.metadataFrameLimit))
        .via(StreamSerialization(serialization).fromBytes[Metadata])
        .recoverWithRetries(1, { case _ ⇒ Source.empty })
    }

    readMetadataFile(regionId, fileId, disposition)
  }

  def delete(regionId: String, fileId: File.ID): Future[Folder] = {
    val path = MetadataStreams.getFolderPath(fileId)
    regionOps.deleteFolder(regionId, path)
  }

  def create(fileName: String, sizeLimit: Long = config.fileSizeLimit): Flow[ByteString, Metadata, NotUsed] = {
    val graph = GraphDSL.create() { implicit builder ⇒
      import GraphDSL.Implicits._
      val bytesInput = builder.add(Broadcast[ByteString](2))

      val extractStream = builder.add(Flow[ByteString].async.prefixAndTail(0).map(_._2))
      val getContentType = builder.add(MimeDetectorStream(modules, fileName, config.mimeProbeSize))

      val zipStreamAndMime = builder.add(Zip[Source[ByteString, NotUsed], String])

      val parseMetadata = builder.add(Flow[(Source[ByteString, NotUsed], String)]
        .flatMapConcat { case (bytes, contentType) ⇒
          if (modules.canParse(fileName, contentType)) {
            bytes.via(modules.parseMetadata(fileName, contentType))
          } else {
            Source.empty
          }
        }
      )

      bytesInput ~> extractStream
      bytesInput ~> getContentType
      extractStream ~> zipStreamAndMime.in0
      getContentType ~> zipStreamAndMime.in1
      zipStreamAndMime.out ~> parseMetadata
      FlowShape(bytesInput.in, parseMetadata.out)
    }

    ByteStringLimit(sizeLimit, truncate = false)
      .via(graph)
      .recoverWithRetries(1, { case _ ⇒ Source.empty })
      .named("metadataCreate")
  }

  def writeFileAndMetadata(regionId: String, path: Path,
                           metadataSizeLimit: Long = config.fileSizeLimit): Flow[ByteString, (File, Seq[File]), NotUsed] = {
    val writeStream = fileStreams.write(regionId, path)
    val createMetadataStream = create(path.name, metadataSizeLimit).buffer(20, OverflowStrategy.dropNew)

    val graph = GraphDSL.create(writeStream, createMetadataStream)(Keep.none) { implicit builder ⇒ (writeFile, createMetadata) ⇒
      import GraphDSL.Implicits._

      val bytesInput = builder.add(Broadcast[ByteString](2))
      val fileInput = builder.add(Broadcast[File](2))

      val extractMetadataSource = builder.add(Flow[Metadata].prefixAndTail(0).map(_._2))
      val zipSourceAndFile = builder.add(Zip[Source[Metadata, NotUsed], File])
      val writeMetadata = builder.add(Flow[(Source[Metadata, NotUsed], File)]
        .flatMapConcat { case (metadataIn, file) ⇒ metadataIn.via(write(regionId, file.id)) }
        .fold(Vector.empty[File])(_ :+ _)
      )
      val zipFileAndMetadata = builder.add(Zip[File, Seq[File]])

      bytesInput ~> writeFile
      bytesInput ~> createMetadata
      createMetadata ~> extractMetadataSource ~> zipSourceAndFile.in0

      writeFile ~> fileInput
      fileInput ~> zipSourceAndFile.in1
      fileInput ~> zipFileAndMetadata.in0

      zipSourceAndFile.out ~> writeMetadata ~> zipFileAndMetadata.in1

      FlowShape(bytesInput.in, zipFileAndMetadata.out)
    }

    Flow.fromGraph(graph)
  }
}
