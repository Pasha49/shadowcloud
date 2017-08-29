package com.karasiq.shadowcloud.actors

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.NotUsed
import akka.actor.{ActorLogging, OneForOneStrategy, Props, Status, SupervisorStrategy}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import akka.util.Timeout

import com.karasiq.shadowcloud.actors.events.SupervisorEvents._
import com.karasiq.shadowcloud.actors.internal.RegionTracker
import com.karasiq.shadowcloud.actors.messages.{RegionEnvelope, StorageEnvelope}
import com.karasiq.shadowcloud.actors.utils.{ActorState, MessageStatus}
import com.karasiq.shadowcloud.config.RegionConfig
import com.karasiq.shadowcloud.exceptions.SupervisorException
import com.karasiq.shadowcloud.model.{RegionId, StorageId}
import com.karasiq.shadowcloud.storage.props.StorageProps

object RegionSupervisor {
  // Messages
  sealed trait Message
  case class AddRegion(regionId: RegionId, regionConfig: RegionConfig) extends Message
  case class DeleteRegion(regionId: RegionId) extends Message
  case class AddStorage(storageId: StorageId, props: StorageProps) extends Message
  case class DeleteStorage(storageId: StorageId) extends Message
  case class RegisterStorage(regionId: RegionId, storageId: StorageId) extends Message
  case class UnregisterStorage(regionId: RegionId, storageId: StorageId) extends Message
  case class SuspendStorage(storageId: StorageId) extends Message
  case class SuspendRegion(regionId: RegionId) extends Message
  case class ResumeStorage(storageId: StorageId) extends Message
  case class ResumeRegion(regionId: RegionId) extends Message
  case object GetSnapshot extends Message with MessageStatus[NotUsed, RegionTracker.Snapshot]

  private[actors] case class RenewRegionSubscriptions(regionId: RegionId) extends Message
  private[actors] case class RenewStorageSubscriptions(storageId: StorageId) extends Message

  // Snapshot
  private[actors] case class RegionSnapshot(config: RegionConfig, storages: Set[String], active: Boolean)
  private[actors] case class StorageSnapshot(props: StorageProps, active: Boolean)
  private[actors] case class Snapshot(regions: Map[String, RegionSnapshot], storages: Map[String, StorageSnapshot])

  // Props
  def props: Props = {
    Props(new RegionSupervisor())
  }
}

private final class RegionSupervisor extends PersistentActor with ActorLogging with RegionSupervisorState {
  import RegionSupervisor._
  import com.karasiq.shadowcloud.actors.events.SupervisorEvents._

  // -----------------------------------------------------------------------
  // Settings
  // -----------------------------------------------------------------------
  private[this] implicit val timeout: Timeout = Timeout(10 seconds)
  val persistenceId: String = "regions"

  // -----------------------------------------------------------------------
  // Recover
  // -----------------------------------------------------------------------
  def receiveRecover: Receive = { // TODO: Create snapshots
    val storages = mutable.AnyRefMap.empty[String, StorageSnapshot]
    val regions = mutable.AnyRefMap.empty[String, RegionSnapshot]
    val recoverFunc: Receive = {
      case SnapshotOffer(_, snapshot: Snapshot) ⇒
        regions.clear()
        regions ++= snapshot.regions
        storages.clear()
        storages ++= snapshot.storages

      case RegionAdded(regionId, regionConfig, active) ⇒
        val storages = regions.get(regionId).fold(Set.empty[String])(_.storages)
        regions += regionId → RegionSnapshot(regionConfig, storages, active)

      case RegionDeleted(regionId) ⇒
        regions -= regionId

      case StorageAdded(storageId, props, active) ⇒
        storages += storageId → StorageSnapshot(props, active)

      case StorageDeleted(storageId) ⇒
        storages -= storageId

      case StorageRegistered(regionId, storageId) if regions.contains(regionId) && storages.contains(storageId) ⇒
        val snapshot = regions(regionId)
        regions += regionId → snapshot.copy(storages = snapshot.storages + storageId)

      case StorageUnregistered(regionId, storageId) if regions.contains(regionId) && storages.contains(storageId) ⇒
        val snapshot = regions(regionId)
        regions += regionId → snapshot.copy(storages = snapshot.storages - storageId)

      case StorageStateChanged(storageId, active) if storages.contains(storageId) ⇒
        val snapshot = storages(storageId)
        storages += storageId → snapshot.copy(active = active)

      case RegionStateChanged(regionId, active) if regions.contains(regionId) ⇒
        val snapshot = regions(regionId)
        regions += regionId → snapshot.copy(active = active)

      case RecoveryCompleted ⇒
        if (log.isDebugEnabled) log.debug("Recovery completed: {} storages, {} regions", storages.size, regions.size)
        loadState(storages, regions)

      case event ⇒
        log.warning("Event unhandled: {}", event)
    }
    recoverFunc
  }

  // -----------------------------------------------------------------------
  // Commands
  // -----------------------------------------------------------------------
  def receiveCommand: Receive = {
    // -----------------------------------------------------------------------
    // Regions
    // -----------------------------------------------------------------------
    case AddRegion(regionId, regionConfig) ⇒
      persist(RegionAdded(regionId, regionConfig, active = true))(updateState)

    case DeleteRegion(regionId) if state.containsRegion(regionId) ⇒
      persist(RegionDeleted(regionId))(updateState)

    // -----------------------------------------------------------------------
    // Storages
    // -----------------------------------------------------------------------
    case AddStorage(storageId, props) ⇒
      persist(StorageAdded(storageId, props, active = true))(updateState)

    case DeleteStorage(storageId) if state.containsStorage(storageId) ⇒
      persist(StorageDeleted(storageId))(updateState)

    // -----------------------------------------------------------------------
    // Storage registration
    // -----------------------------------------------------------------------
    case RegisterStorage(regionId, storageId) if state.containsRegionAndStorage(regionId, storageId) ⇒
      persist(StorageRegistered(regionId, storageId))(updateState)

    case UnregisterStorage(regionId, storageId) if state.containsRegionAndStorage(regionId, storageId) ⇒
      persist(StorageUnregistered(regionId, storageId))(updateState)

    // -----------------------------------------------------------------------
    // State actions
    // -----------------------------------------------------------------------
    case SuspendStorage(storageId) ⇒
      persist(StorageStateChanged(storageId, active = false))(updateState)

    case ResumeStorage(storageId) ⇒
      persist(StorageStateChanged(storageId, active = true))(updateState)

    case SuspendRegion(regionId) ⇒
      persist(RegionStateChanged(regionId, active = false))(updateState)

    case ResumeRegion(regionId) ⇒
      persist(RegionStateChanged(regionId, active = true))(updateState)

    case GetSnapshot ⇒
      deferAsync(NotUsed)(_ ⇒ sender() ! GetSnapshot.Success(NotUsed, state.getSnapshot()))

    case RenewRegionSubscriptions(regionId) ⇒
      deferAsync(NotUsed)(_ ⇒ state.registerRegionStorages(regionId))

    case RenewStorageSubscriptions(storageId) ⇒
      deferAsync(NotUsed)(_ ⇒ state.registerStorageRegions(storageId))

    // -----------------------------------------------------------------------
    // Envelopes
    // -----------------------------------------------------------------------
    case RegionEnvelope(regionId, message) ⇒
      if (state.containsRegion(regionId)) {
        state.getRegion(regionId).actorState match {
          case ActorState.Active(dispatcher) ⇒
            dispatcher.forward(message)

          case ActorState.Suspended ⇒
            sender() ! Status.Failure(SupervisorException.IllegalRegionState(regionId,
              new IllegalStateException("Region is suspended")))
        }
      } else {
        sender() ! Status.Failure(SupervisorException.RegionNotFound(regionId))
      }

    case StorageEnvelope(storageId, message) ⇒
      if (state.containsStorage(storageId)) {
        state.getStorage(storageId).actorState match {
          case ActorState.Active(dispatcher) ⇒
            dispatcher.forward(message)

          case ActorState.Suspended ⇒
            sender() ! Status.Failure(SupervisorException.IllegalStorageState(storageId,
              new IllegalStateException("Storage is suspended")))
        }
      } else {
        sender() ! Status.Failure(SupervisorException.StorageNotFound(storageId))
      }
  }

  // -----------------------------------------------------------------------
  // Supervisor strategy
  // -----------------------------------------------------------------------
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: IllegalArgumentException ⇒
      SupervisorStrategy.Resume
  }
}

private sealed trait RegionSupervisorState { self: RegionSupervisor ⇒
  import RegionSupervisor._

  val state = RegionTracker()

  def updateState(event: Event): Unit = event match {
    // -----------------------------------------------------------------------
    // Virtual regions
    // -----------------------------------------------------------------------
    case RegionAdded(regionId, regionConfig, active) ⇒
      log.info("Region added: {}", regionId)
      state.addRegion(regionId, regionConfig)
      if (active) state.resumeRegion(regionId) else state.suspendRegion(regionId)

    case RegionDeleted(regionId) if state.containsRegion(regionId) ⇒
      log.debug("Region deleted: {}", regionId)
      state.deleteRegion(regionId)

    // -----------------------------------------------------------------------
    // Storages
    // -----------------------------------------------------------------------
    case StorageAdded(storageId, props, active) ⇒
      log.info("Storage added: {} (props = {})", storageId, props)
      state.addStorage(storageId, props)
      if (active) state.resumeStorage(storageId) else state.suspendStorage(storageId)

    case StorageDeleted(storageId) if state.containsStorage(storageId) ⇒
      log.info("Storage deleted: {}", storageId)
      state.deleteStorage(storageId)

    // -----------------------------------------------------------------------
    // Storage registration
    // -----------------------------------------------------------------------
    case StorageRegistered(regionId, storageId) if state.containsRegionAndStorage(regionId, storageId) ⇒
      log.info("Storage {} registered in {}", storageId, regionId)
      state.registerStorage(regionId, storageId)

    case StorageUnregistered(regionId, storageId) if state.containsRegionAndStorage(regionId, storageId) ⇒
      log.info("Storage {} unregistered from {}", storageId, regionId)
      state.unregisterStorage(regionId, storageId)

    // -----------------------------------------------------------------------
    // Suspend/resume
    // -----------------------------------------------------------------------
    case RegionStateChanged(regionId, active) if state.containsRegion(regionId) ⇒
      log.debug("Region {} state changed to {}", regionId, if (active) "active" else "suspended")
      if (active) state.resumeRegion(regionId) else state.suspendRegion(regionId)

    case StorageStateChanged(storageId, active) if state.containsStorage(storageId) ⇒
      log.debug("Storage {} state changed to {}", storageId, if (active) "active" else "suspended")
      if (active) state.resumeStorage(storageId) else state.suspendStorage(storageId)

    case _ ⇒
      log.warning("Unhandled event: {}", event)
  }

  def loadState(storages: collection.Map[String, StorageSnapshot], regions: collection.Map[String, RegionSnapshot]): Unit = {
    state.clear()
    storages.foreach { case (storageId, StorageSnapshot(props, active)) ⇒ 
      updateState(StorageAdded(storageId, props, active))
    }
    regions.foreach { case (regionId, RegionSnapshot(regionConfig, storages, active)) ⇒
      updateState(RegionAdded(regionId, regionConfig, active))
      storages.foreach(storageId ⇒ updateState(StorageRegistered(regionId, storageId)))
    }
  }
}
