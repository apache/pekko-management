/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.coordination.lease.kubernetes

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko
import pekko.actor.Status.Failure
import pekko.actor.{ ActorRef, DeadLetterSuppression, FSM, LoggingFSM, Props }
import pekko.annotation.InternalApi
import pekko.coordination.lease.{ LeaseSettings, LeaseTimeoutException }
import pekko.util.ConstantFun
import pekko.util.PrettyDuration._

import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object LeaseActor {

  sealed trait State
  case object Idle extends State
  case object PendingRead extends State
  case object Granting extends State
  case object Granted extends State
  case object Releasing extends State

  sealed trait Data
  case object ReadRequired extends Data

  // Known version from when the lease was cleared. A subsequent update can try without reading
  // with the given version as it was from an update that set client to None
  case class LeaseCleared(version: String) extends Data

  sealed trait ReplyRequired {
    def replyTo: ActorRef

    /**
     * Callers that asked to acquire the same lease while this operation was already in flight.
     * They get the same response as [[replyTo]] once the operation completes.
     */
    def alsoReplyTo: Set[ActorRef]

    def allReplyTo: Set[ActorRef] = alsoReplyTo + replyTo
  }
  // Awaiting a read to try and get the lease
  case class PendingReadData(
      replyTo: ActorRef,
      leaseLostCallback: Option[Throwable] => Unit,
      alsoReplyTo: Set[ActorRef] = Set.empty)
      extends Data
      with ReplyRequired
  case class OperationInProgress(
      replyTo: ActorRef,
      version: String,
      leaseLostCallback: Option[Throwable] => Unit,
      operationStartTime: Long = System.nanoTime(),
      releaseRetries: Int = 0,
      alsoReplyTo: Set[ActorRef] = Set.empty)
      extends Data
      with ReplyRequired
  case class GrantedVersion(
      version: String,
      leaseLostCallback: Option[Throwable] => Unit,
      heartbeatFailures: Int = 0)
      extends Data

  sealed trait Command
  case class Acquire(leaseLostCallback: Option[Throwable] => Unit = ConstantFun.scalaAnyToUnit) extends Command
  case class Release() extends Command

  // internal
  private case class ReadResponse(response: LeaseResource) extends Command
  private case class WriteResponse(response: Either[LeaseResource, LeaseResource]) extends Command
  private case object Heartbeat extends Command
  private case object HeartbeatRetry extends Command
  private case object ReleaseRetry extends Command

  sealed trait Response
  case object LeaseAcquired extends Response
  case object LeaseTaken extends Response
  case object LeaseReleased extends Response with DeadLetterSuppression
  case class InvalidRequest(reason: String) extends Response with DeadLetterSuppression

  def props(
      k8sApi: KubernetesApi,
      settings: LeaseSettings,
      leaseName: String,
      granted: AtomicBoolean,
      heartbeatMaxRetries: Int,
      releaseMaxRetries: Int): Props = {
    Props(new LeaseActor(k8sApi, settings, leaseName, granted, heartbeatMaxRetries, releaseMaxRetries))
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] class LeaseActor(
    k8sApi: KubernetesApi,
    settings: LeaseSettings,
    leaseName: String,
    granted: AtomicBoolean,
    heartbeatMaxRetries: Int,
    releaseMaxRetries: Int)
    extends LoggingFSM[LeaseActor.State, LeaseActor.Data] {

  import pekko.pattern.pipe
  import context.dispatcher
  import LeaseActor._

  private val ownerName = settings.ownerName

  startWith(Idle, ReadRequired)

  when(Idle) {
    case Event(Acquire(leaseLostCallback), ReadRequired) =>
      // Send off read, pipe result back to self
      pipe(k8sApi.readOrCreateLeaseResource(leaseName).map(ReadResponse.apply)).to(self)
      goto(PendingRead).using(PendingReadData(sender(), leaseLostCallback))

    // Initial read can be skipped as we have a version
    case Event(Acquire(leaseLostCallback), LeaseCleared(version)) =>
      pipe(k8sApi.updateLeaseResource(leaseName, ownerName, version).map(r => WriteResponse(r))).to(self)
      goto(Granting).using(OperationInProgress(sender(), version, leaseLostCallback))
  }

  when(PendingRead) {
    // Lock not taken
    case Event(ReadResponse(LeaseResource(None, version, _)), prd @ PendingReadData(who, leaseLost, _)) =>
      tryGetLease(version, who, leaseLost, prd.alsoReplyTo)
    case Event(
          ReadResponse(LeaseResource(Some(currentOwner), version, time)),
          prd @ PendingReadData(who, leaseLost, _)) if currentOwner == ownerName =>
      // We have the lock from a different incarnation
      if (hasLeaseTimedOut(time)) {
        log.warning(
          "Lease {} requested by client {} is already owned by client. Previous lease was not released due to ungraceful shutdown. " +
          "Lease time {} is close or past expiry so re-acquiring",
          leaseName,
          ownerName,
          time)
        tryGetLease(version, who, leaseLost, prd.alsoReplyTo)
      } else {
        log.warning(
          "Lease {} requested by client {} is already owned by client. Previous lease was not released due to ungraceful shutdown. " +
          "Lease is still within timeout so granting immediately",
          leaseName,
          ownerName)
        replyToAll(prd, LeaseAcquired)
        goto(Granted).using(GrantedVersion(version, leaseLost))
      }
    case Event(ReadResponse(LeaseResource(Some(currentOwner), version, time)),
          prd @ PendingReadData(who, leaseLost, _)) =>
      if (hasLeaseTimedOut(time)) {
        log.warning(
          "Lease {} has reached TTL. Owner {} has failed to heartbeat, have they crashed?. Allowing {} to try and take lease",
          leaseName,
          currentOwner,
          ownerName)
        tryGetLease(version, who, leaseLost, prd.alsoReplyTo)
      } else {
        replyToAll(prd, LeaseTaken)
        // Even though we have a version there is no benefit to storing it as we can't update a lease that has a client
        goto(Idle).using(ReadRequired)
      }
  }

  when(Granting) {
    case Event(
          WriteResponse(Right(response)),
          cc @ OperationInProgress(_, oldVersion, leaseLost, operationStartTime, _, _)) =>
      require(
        oldVersion != response.version,
        s"Update response from Kubernetes API should not return the same version: Response: $response. Client: $cc")
      val operationDuration = System.nanoTime() - operationStartTime
      if (operationDuration > (settings.timeoutSettings.heartbeatTimeout.toNanos / 2)) {
        log.warning("API server took too long to respond to update: {}. ", operationDuration.nanos.pretty)
        replyToAll(cc,
          Failure(new LeaseTimeoutException(s"API server took too long to respond: ${operationDuration.nanos.pretty}")))
        goto(Idle).using(ReadRequired)
      } else {
        granted.set(true)
        replyToAll(cc, LeaseAcquired)
        goto(Granted).using(GrantedVersion(response.version, leaseLost))
      }

    case Event(WriteResponse(Left(LeaseResource(None, version, _))),
          op @ OperationInProgress(_, oldVersion, _, startTime, _, _)) =>
      require(oldVersion != version)
      val operationDuration = (System.nanoTime() - startTime).nanos
      if (operationDuration > settings.timeoutSettings.operationTimeout) {
        // The lease version keeps moving on. Give up rather than retrying for longer than the
        // caller is prepared to wait, otherwise the lease could be granted after the caller has
        // already been told the acquire failed.
        log.warning(
          "Failed to acquire lease {} for owner {} after {}: lease version kept moving on.",
          leaseName,
          ownerName,
          operationDuration.pretty)
        replyToAll(op,
          Failure(new LeaseTimeoutException(
            s"Timed out trying to acquire lease [$leaseName, $ownerName] after ${operationDuration.pretty}")))
        goto(Idle).using(ReadRequired)
      } else {
        // Try again as lock version has moved on but is not taken.
        // Do not reply yet — wait for the retry to succeed. Record the version being attempted so
        // that a subsequent conflict is compared against it rather than against the original version.
        pipe(k8sApi.updateLeaseResource(leaseName, ownerName, version).map(r => WriteResponse(r))).to(self)
        stay().using(op.copy(version = version))
      }
    case Event(WriteResponse(Left(LeaseResource(Some(_), _, _))), op: OperationInProgress) =>
      // The audacity, someone else has taken the lease :(
      replyToAll(op, LeaseTaken)
      goto(Idle).using(ReadRequired) // can't use version as another owner has the lock
  }

  when(Granted) {
    case Event(Heartbeat, GrantedVersion(version, _, _)) =>
      log.debug("Heartbeat: updating lease time. Version {}", version)
      pipe(k8sApi.updateLeaseResource(leaseName, ownerName, version).map(WriteResponse.apply)).to(self)
      stay()
    case Event(HeartbeatRetry, GrantedVersion(version, _, _)) =>
      log.debug("Heartbeat retry: updating lease time. Version {}", version)
      pipe(k8sApi.updateLeaseResource(leaseName, ownerName, version).map(WriteResponse.apply)).to(self)
      stay()
    case Event(WriteResponse(Right(resource)), gv: GrantedVersion) =>
      require(
        resource.owner.contains(ownerName),
        "response from API server has different owner for success: " + resource)
      log.debug("Heartbeat: lease time updated: Version {}", resource.version)
      startSingleTimer("heartbeat", Heartbeat, settings.timeoutSettings.heartbeatInterval)
      stay().using(gv.copy(version = resource.version, heartbeatFailures = 0))
    case Event(WriteResponse(Left(lr @ _)), GrantedVersion(_, leaseLost, _)) =>
      log.warning("Conflict during heartbeat to lease {}. Lease assumed to be released.", lr)
      granted.set(false)
      executeLeaseLockCallback(leaseLost, None)
      goto(Idle).using(ReadRequired)
    case Event(Failure(t), gv @ GrantedVersion(_, leaseLost, failures)) =>
      if (failures < heartbeatMaxRetries) {
        log.warning(
          "Failure during heartbeat to lease: [{}]. Retrying (attempt {}/{}).",
          t.getMessage,
          failures + 1,
          heartbeatMaxRetries)
        val retryDelay = settings.timeoutSettings.heartbeatInterval / (heartbeatMaxRetries + 1)
        startSingleTimer("heartbeat-retry", HeartbeatRetry, retryDelay)
        stay().using(gv.copy(heartbeatFailures = failures + 1))
      } else {
        log.warning(
          "Failure during heartbeat to lease: [{}]. Retries exhausted. Lease assumed to be released.",
          t.getMessage)
        granted.set(false)
        executeLeaseLockCallback(leaseLost, Some(t))
        goto(Idle).using(ReadRequired)
      }
    case Event(Release(), GrantedVersion(version, leaseLost, _)) =>
      pipe(k8sApi.updateLeaseResource(leaseName, "", version).map(WriteResponse.apply)).to(self)
      goto(Releasing).using(OperationInProgress(sender(), version, leaseLost))
    case Event(Acquire(leaseLostCallback), gv: GrantedVersion) =>
      sender() ! LeaseAcquired
      stay().using(gv.copy(leaseLostCallback = leaseLostCallback))
  }

  private def executeLeaseLockCallback(callback: Option[Throwable] => Unit, result: Option[Throwable]): Unit =
    try {
      callback(result)
    } catch {
      case NonFatal(t) =>
        log.warning("Lease lost callback threw exception: {}", t)
    }

  when(Releasing) {
    case Event(WriteResponse(Right(lr)), OperationInProgress(who, _, _, _, _, _)) =>
      require(lr.owner.isEmpty, "Released lease has unexpected owner: " + lr)
      who ! LeaseReleased
      goto(Idle).using(LeaseCleared(lr.version))
    case Event(WriteResponse(Left(lr @ LeaseResource(None, _, _))), OperationInProgress(who, _, _, _, _, _)) =>
      log.warning(
        "Release conflict and owner has been removed: {}. Lease will continue to work but TTL must have been reached to allow another node to remove lease.",
        lr)
      who ! LeaseReleased
      goto(Idle).using(ReadRequired)
    case Event(WriteResponse(Left(lr @ LeaseResource(Some(_), _, _))), OperationInProgress(who, _, _, _, _, _)) =>
      log.warning(
        "Release conflict and owner has changed: {}. Lease will continue to work but TTL must have been reached to allow another node to update the lease.",
        lr)
      who ! LeaseReleased
      goto(Idle).using(ReadRequired)
    case Event(Failure(t), op @ OperationInProgress(who, _, _, startTime, retries, _)) =>
      // Pace release retries off the lease operation timeout rather than the heartbeat interval:
      // the caller is waiting on an ask that uses the operation timeout, so retries that run past
      // it would leave the caller with an ask timeout and the reply in dead letters.
      val operationTimeout = settings.timeoutSettings.operationTimeout
      val retryDelay = operationTimeout / (releaseMaxRetries + 1)
      val elapsed = (System.nanoTime() - startTime).nanos
      if (retries < releaseMaxRetries && (elapsed + retryDelay) < operationTimeout) {
        log.warning(
          "Failure releasing lease: [{}]. Retrying in {} (attempt {}/{}).",
          t.getMessage,
          retryDelay.pretty,
          retries + 1,
          releaseMaxRetries)
        startSingleTimer("release-retry", ReleaseRetry, retryDelay)
        stay().using(op.copy(releaseRetries = retries + 1))
      } else {
        log.warning("Failure releasing lease: [{}]. Retries exhausted.", t.getMessage)
        who ! Failure(t)
        goto(Idle).using(ReadRequired)
      }
    case Event(ReleaseRetry, OperationInProgress(_, version, _, _, _, _)) =>
      log.debug("Release retry: releasing lease. Version {}", version)
      pipe(k8sApi.updateLeaseResource(leaseName, "", version).map(WriteResponse.apply)).to(self)
      stay()
    case Event(Acquire(_), _) =>
      // Acquiring while a release of the same lease is in flight is contradictory, so unlike an
      // acquire during an in-flight acquire this is rejected rather than queued.
      log.info(
        "Acquire request for owner {} lease {} while a release is in progress.",
        ownerName,
        leaseName)
      sender() ! InvalidRequest("Tried to acquire a lease while a release is in progress")
      stay()
  }

  whenUnhandled {
    case Event(Acquire(leaseLostCallback), data: ReplyRequired) =>
      // An acquire for the same lease is already in flight. Queue this caller rather than
      // rejecting it: they all want the same outcome and will get the same response.
      log.info(
        "Acquire request for owner {} lease {} while a previous acquire is still in progress, " +
        "the caller will get the result of that acquire. Current state: {}",
        ownerName,
        leaseName,
        stateName)
      stay().using(addAcquirer(data, sender(), leaseLostCallback))
    case Event(Release(), data @ _) =>
      log.info(
        "Release request for owner {} lease {} while previous acquire/release still in progress. Current state: {}",
        ownerName,
        leaseName,
        stateName)
      sender() ! InvalidRequest("Tried to release a lease that is not acquired")
      stay().using(data)
    case Event(Failure(t), replyRequired: ReplyRequired) =>
      log.warning(
        "Failure communicating with the API server for owner {} lease {}: [{}]. Current state: {}",
        ownerName,
        leaseName,
        t.getMessage,
        stateName)
      replyToAll(replyRequired, Failure(t))
      goto(Idle).using(ReadRequired)
  }

  private def replyToAll(data: ReplyRequired, response: Any): Unit =
    data.allReplyTo.foreach(_ ! response)

  /**
   * Add a caller that asked to acquire the lease while an acquire was already in flight. The most
   * recently supplied lease lost callback wins, matching the re-acquire behaviour in `Granted`.
   */
  private def addAcquirer(data: ReplyRequired, who: ActorRef, leaseLost: Option[Throwable] => Unit): Data =
    data match {
      case prd: PendingReadData =>
        prd.copy(leaseLostCallback = leaseLost, alsoReplyTo = prd.alsoReplyTo + who)
      case op: OperationInProgress =>
        op.copy(leaseLostCallback = leaseLost, alsoReplyTo = op.alsoReplyTo + who)
    }

  onTransition {
    case _ -> Granted =>
      startSingleTimer("heartbeat", Heartbeat, settings.timeoutSettings.heartbeatInterval)
    case Granted -> _ =>
      cancelTimer("heartbeat")
      cancelTimer("heartbeat-retry")
      granted.set(false)
    case Releasing -> _ =>
      cancelTimer("release-retry")
  }

  private def tryGetLease(
      version: String,
      reply: ActorRef,
      leaseLost: Option[Throwable] => Unit,
      alsoReplyTo: Set[ActorRef]): FSM.State[LeaseActor.State, Data] = {
    pipe(k8sApi.updateLeaseResource(leaseName, ownerName, version).map(r => WriteResponse(r))).to(self)
    goto(Granting).using(OperationInProgress(reply, version, leaseLost, alsoReplyTo = alsoReplyTo))
  }

  private def hasLeaseTimedOut(leaseTime: Long): Boolean = {
    System
      .currentTimeMillis() >
    (leaseTime +
    settings.timeoutSettings.heartbeatTimeout
      .toMillis - (2 * settings.timeoutSettings.heartbeatInterval.toMillis))
  }
}
