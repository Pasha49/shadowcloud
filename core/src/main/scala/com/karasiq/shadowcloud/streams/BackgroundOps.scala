package com.karasiq.shadowcloud.streams

import scala.concurrent.Future

import akka.stream._
import akka.stream.scaladsl.Source

import com.karasiq.shadowcloud.config.SCConfig
import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.model.RegionId

private[shadowcloud] object BackgroundOps {
  def apply(config: SCConfig, regionOps: RegionOps)(implicit mat: Materializer): BackgroundOps = {
    new BackgroundOps(config, regionOps)
  }
}

private[shadowcloud] final class BackgroundOps(config: SCConfig, regionOps: RegionOps)(implicit mat: Materializer) {
  private[this] val repairStream = RegionRepairStream(config.parallelism, regionOps)
    .addAttributes(Attributes.name("regionRepairQueue") and ActorAttributes.supervisionStrategy(Supervision.resumingDecider))

  def repair(regionId: RegionId, strategy: RegionRepairStream.Strategy, chunks: Seq[Chunk] = Nil): Future[Seq[Chunk]] = { // TODO: Queue
    val request = RegionRepairStream.Request(regionId, strategy, chunks)
    Source.single(request)
      .to(repairStream)
      .mapMaterializedValue(_ ⇒ request.result.future)
      .run()
  }
}
