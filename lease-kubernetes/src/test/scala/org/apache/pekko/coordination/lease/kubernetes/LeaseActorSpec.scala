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
import pekko.actor.{ ActorRef, ActorSystem }
import pekko.coordination.lease.kubernetes.LeaseActor._
import pekko.coordination.lease.{ LeaseException, LeaseSettings, LeaseTimeoutException, TimeoutSettings }
import pekko.pattern.ask
import pekko.testkit.{ TestDuration, TestKit, TestProbe }
import pekko.util.{ ConstantFun, Timeout }
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MockKubernetesApi(system: ActorSystem, currentLease: ActorRef, updateLease: ActorRef) extends KubernetesApi {

  implicit val timeout: Timeout = Timeout(10.seconds)

  override def readOrCreateLeaseResource(name: String): Future[LeaseResource] = {
    currentLease.ask(name).mapTo[LeaseResource]
  }

  override def updateLeaseResource(
      leaseName: String,
      clientName: String,
      version: String,
      time: Long): Future[Either[LeaseResource, LeaseResource]] = {
    updateLease.ask((clientName, version)).mapTo[Either[LeaseResource, LeaseResource]]
  }
}

class LeaseActorSpec
    extends TestKit(
      ActorSystem(
        "LeaseActorSpec",
        ConfigFactory.parseString("""
    pekko.loggers = []
    pekko.loglevel = DEBUG
    pekko.stdout-loglevel = DEBUG
    pekko.actor.debug.fsm = true
  """)))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val leaseName = "sbr"

  // How far the lease version is moved on by other clients when simulating a conflict. Any value
  // greater than zero works: the test only needs a version ahead of the one the actor sent.
  val otherClientUpdates = 6

  "LeaseActor" should {

    // TODO what if the same client asks for the lease when granting? respond to both or ignore?

    "acquire empty lease" in new Test {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))

      // as no one owns the lock get the lock
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
    }

    "handle failure in initial read" in new Test {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(Failure(k8sApiFailure))
      senderProbe.expectMsg(Failure(k8sApiFailure))
    }

    "allow acquire after initial failure on read" in new Test {
      k8sApiFailureDuringRead()
      acquireLease()
    }

    "allow client to re-acquire the same lease" in new Test {
      acquireLease()
      underTest ! Acquire()
      senderProbe.expectMsg(LeaseAcquired)
    }

    "fail if granting takes longer than the heartbeat timeout" in new Test {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))

      // too slow, could have already timed out
      updateProbe.expectNoMessage(leaseSettings.timeoutSettings.heartbeatTimeout * 2)
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      // not granted
      senderProbe.expectMsgType[Failure].cause.getMessage should startWith("API server took too long to respond")
      granted.get() shouldEqual false

      // should allow retry
      acquireLease()

    }

    // FIXME, give up if API server is constantly slow to respond

    "reject taken lease in state idle" in new Test {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(Some("a different client"), currentVersion, System.currentTimeMillis()))
      senderProbe.expectMsg(LeaseTaken)
    }

    "heartbeat granted lease" in new Test {
      acquireLease()

      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      updateProbe.expectMsg((ownerName, currentVersion))

      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      updateProbe.expectMsg((ownerName, currentVersion))
    }

    "remove lease from k8s when released" in new Test {
      acquireLease()
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))
    }

    "remove lease from k8s conflict during update but lease has removed" in new Test {
      // "should not happen TM"
      acquireLease()
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(None, currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseReleased)
    }

    "remove lease from k8s conflict during update but lease taken by another" in new Test {
      // "should not happen TM"
      acquireLease()
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(Some("another client"), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseReleased)
    }

    "remove lease from k8s failure" in new Test {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      acquireLease()
      underTest ! Release()
      failAllReleaseAttempts(k8sApiFailure)
      senderProbe.expectMsg(Failure(k8sApiFailure))
    }

    "sets granted when granted" in new Test {
      granted.get shouldEqual false
      acquireLease()
      awaitAssert {
        granted.get shouldEqual true
      }
    }

    "sets granted when acquired and released" in new Test {
      granted.get shouldEqual false
      acquireLease()
      awaitAssert {
        granted.get === true
      }
      releaseLease()
      awaitAssert {
        granted.get === false
      }
    }

    "released lock should be acquirable" in new Test {
      acquireLease()
      releaseLease()
      // Version from the previous lock so can skip the read of the resource unless the CAS fails
      acquireLeaseWithoutRead(ownerName)
    }

    "released lock acquired with new version" in new Test {
      acquireLease()
      releaseLease()

      // Version from the previous lock so can skip the read of the resource unless the CAS fails
      underTest ! LeaseActor.Acquire()
      updateProbe.expectMsg((ownerName, currentVersion))
      // Fail due to cas, the version has moved on but no one owns the lock
      val failedVersion = currentVersionCount + otherClientUpdates
      updateProbe.reply(Left(LeaseResource(None, failedVersion.toString, System.currentTimeMillis())))
      // Try again, a successful update moves the version on again
      updateProbe.expectMsg((ownerName, failedVersion.toString))
      currentVersionCount = failedVersion + 1
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
    }

    "heartbeat conflict should set granted to false" in new Test {
      acquireLease()
      expectHeartBeat()
      granted.get() shouldEqual true

      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(Some("i stole your lock"), currentVersion, System.currentTimeMillis())))
      awaitAssert {
        granted.get() shouldEqual false
      }
    }

    "heartbeat conflict should call lease lost callback" in new Test {
      @volatile var callbackCalled: Option[Throwable] = None
      acquireLease(reason => callbackCalled = reason)
      expectHeartBeat()
      granted.get() shouldEqual true

      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(Some("i stole your lock"), currentVersion, System.currentTimeMillis())))
      awaitAssert {
        callbackCalled shouldEqual None
      }
    }

    "heartbeat fail should set granted to false after retries exhausted" in new Test {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      acquireLease()
      expectHeartBeat()
      granted.get() shouldEqual true

      failAllHeartbeatAttempts(k8sApiFailure)
      awaitAssert {
        granted.get() shouldEqual false
      }
    }

    "heartbeat fail should call lease lost callback after retries exhausted" in new Test {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      @volatile var callbackCalled: Option[Throwable] = None
      acquireLease(reason => callbackCalled = reason)
      expectHeartBeat()
      granted.get() shouldEqual true

      failAllHeartbeatAttempts(k8sApiFailure)
      awaitAssert {
        callbackCalled shouldEqual Some(k8sApiFailure)
      }
    }

    "lock should be acquirable after heart beat conflict" in new Test {
      acquireLease()
      expectHeartBeat()
      heartBeatConflict()
      acquireLease()
    }

    "lock should be acquirable after heart beat fail" in new Test {
      acquireLease()
      expectHeartBeat()
      heartBeatFailure()
      acquireLease()
    }

    "reply LeaseAcquired to both callers when acquire arrives while read is pending" in new Test {
      val secondSender = TestProbe()
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)

      // second acquire while first is still pending, only one read/update is issued for both
      underTest.tell(LeaseActor.Acquire(), secondSender.ref)
      leaseProbe.expectNoMessage(100.millis)

      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))

      senderProbe.expectMsg(LeaseAcquired)
      secondSender.expectMsg(LeaseAcquired)
    }

    "reply LeaseAcquired to both callers when acquire arrives while grant is in progress" in new Test {
      val secondSender = TestProbe()
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))

      // second acquire while granting, no extra update is issued
      underTest.tell(LeaseActor.Acquire(), secondSender.ref)
      updateProbe.expectNoMessage(100.millis)

      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))

      senderProbe.expectMsg(LeaseAcquired)
      secondSender.expectMsg(LeaseAcquired)
    }

    "reply LeaseTaken to both callers when the lease turns out to be taken" in new Test {
      val secondSender = TestProbe()
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      underTest.tell(LeaseActor.Acquire(), secondSender.ref)

      leaseProbe.reply(LeaseResource(Some("someone else"), currentVersion, System.currentTimeMillis()))

      senderProbe.expectMsg(LeaseTaken)
      secondSender.expectMsg(LeaseTaken)
    }

    "reply the failure to both callers when the in progress acquire fails" in new Test {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      val secondSender = TestProbe()
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      underTest.tell(LeaseActor.Acquire(), secondSender.ref)

      leaseProbe.reply(Failure(k8sApiFailure))

      senderProbe.expectMsg(Failure(k8sApiFailure))
      secondSender.expectMsg(Failure(k8sApiFailure))
    }

    "reply InvalidRequest when acquire arrives while a release is in progress" in new Test {
      val secondSender = TestProbe()
      acquireLease()
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))

      underTest.tell(LeaseActor.Acquire(), secondSender.ref)
      secondSender.expectMsg(InvalidRequest("Tried to acquire a lease while a release is in progress"))

      // the release itself is unaffected
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(None, currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseReleased)
    }

    "return lease taken if conflict when updating lease" in new Test {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(Some("some one else :("), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseTaken)
    }

    "be able to get lease after failing previous grant update" in new Test {
      failToGetLeaseDuringGrantingUpdate()
      acquireLease()
    }

    "allow lease to be overwritten if TTL expired (from IDLE state, need version read)" in new Test {
      val crashedClient = "crashedClient"

      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      // lease is now older than the timeout, ahhhh
      leaseProbe.reply(
        LeaseResource(
          Some(crashedClient),
          currentVersion,
          System.currentTimeMillis() - (leaseSettings.timeoutSettings.heartbeatTimeout.toMillis * 2)))
      // try and get the lease
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
    }

    "allow lease to be overwritten if TTL expired (after previous failed attempt)" in new Test {
      val crashedClient = "crashedClient"
      failToGetTakenLease(crashedClient)
      // Second try the TTL is reached
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      // lease is now older than the timeout, ahhhh
      leaseProbe.reply(
        LeaseResource(
          Some(crashedClient),
          currentVersion,
          System.currentTimeMillis() - (leaseSettings.timeoutSettings.heartbeatTimeout.toMillis * 2)))
      // try and get the lease
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
    }

    // If we crash and then come back and read our own client name back AND it hasn't timed out
    "allow lease to be taken if owned by same client name from IDLE" in new Test {
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis()))
      updateProbe.expectNoMessage(leaseSettings.timeoutSettings.heartbeatInterval / 2) // no time update required
      senderProbe.expectMsg(LeaseAcquired)
      expectHeartBeat()
    }

    // If we crash and read our own client name back and it has timed out it needs a time update
    // in this case another node could be trying to get the lease so we should go through
    // the full granting process
    "renew time if lease is owned by client on initial acquire" in new Test {
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(
        LeaseResource(
          Some(ownerName),
          currentVersion,
          System.currentTimeMillis() - (leaseSettings.timeoutSettings.heartbeatTimeout.toMillis * 2)))
      senderProbe.expectNoMessage(leaseSettings.timeoutSettings.heartbeatTimeout / 3) // not grated yet
      updateProbe.expectMsg((ownerName, currentVersion)) // update time
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
      expectHeartBeat()
    }

  }

  trait Test {
    val ownerName = "owner1"
    def timeoutSettings: TimeoutSettings = new TimeoutSettings(25.millis, 250.millis, 1.second)
    val leaseSettings: LeaseSettings = new LeaseSettings(
      leaseName,
      ownerName,
      timeoutSettings,
      ConfigFactory.empty())

    var currentVersionCount = 1
    def currentVersion = currentVersionCount.toString
    def incrementVersion() = currentVersionCount += 1
    val leaseProbe = TestProbe()
    val updateProbe = TestProbe()
    val mockKubernetesApi = new MockKubernetesApi(system, leaseProbe.ref, updateProbe.ref)
    val granted = new AtomicBoolean(false)
    def heartbeatMaxRetries: Int = 3
    def releaseMaxRetries: Int = 3
    val underTest = system.actorOf(
      LeaseActor.props(mockKubernetesApi, leaseSettings, leaseSettings.leaseName, granted, heartbeatMaxRetries,
        releaseMaxRetries))
    val senderProbe = TestProbe()
    implicit val sender: ActorRef = senderProbe.ref

    def expectHeartBeat(): Unit = {
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
    }

    def failToGetTakenLease(leaseOwner: String): Unit = {
      underTest.tell(LeaseActor.Acquire(), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(Some(leaseOwner), currentVersion, System.currentTimeMillis()))
      senderProbe.expectMsg(LeaseTaken)
    }

    def acquireLease(callback: Option[Throwable] => Unit = ConstantFun.scalaAnyToUnit): Unit = {
      underTest.tell(LeaseActor.Acquire(callback), senderProbe.ref)
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, 1L))
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
    }

    def acquireLeaseWithoutRead(clientName: String): Unit = {
      underTest ! LeaseActor.Acquire()
      updateProbe.expectMsg((clientName, currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(Some(clientName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
    }

    def releaseLease(): Unit = {
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(None, currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseReleased)
    }

    def goToGrantingFromIdle(clientName: String): Unit = {
      underTest ! Acquire()
      // get the current state
      incrementVersion()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((clientName, currentVersion))
    }

    def failToGetLeaseDuringGrantingUpdate(): Unit = {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(Some("some one else :("), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseTaken)
    }

    def k8sApiFailureDuringRead(): Unit = {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(Failure(k8sApiFailure))
      senderProbe.expectMsg(Failure(k8sApiFailure))
    }

    def heartBeatConflict(): Unit = {
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(Some("i stole your lock"), currentVersion, System.currentTimeMillis())))
      awaitAssert {
        granted.get() shouldEqual false
      }
    }

    /**
     * Fail the initial heartbeat and every retry of it. A failed heartbeat does not move the lease
     * version on, so the same version is expected for each attempt.
     */
    def failAllHeartbeatAttempts(failure: Throwable): Unit =
      for (_ <- 0 to heartbeatMaxRetries) {
        updateProbe.expectMsg((ownerName, currentVersion))
        updateProbe.reply(Failure(failure))
      }

    /** Fail the initial release and every retry of it. */
    def failAllReleaseAttempts(failure: Throwable): Unit =
      for (_ <- 0 to releaseMaxRetries) {
        updateProbe.expectMsg(("", currentVersion))
        updateProbe.reply(Failure(failure))
      }

    def heartBeatFailure(): Unit = {
      failAllHeartbeatAttempts(new LeaseException("Failed to communicate with API server"))
      awaitAssert {
        granted.get() shouldEqual false
      }
    }

  }

  trait NoRetryTest extends Test {
    override def heartbeatMaxRetries: Int = 0
    override def releaseMaxRetries: Int = 0
  }

  "LeaseActor with retries disabled" should {

    "immediately release lease on heartbeat failure" in new NoRetryTest {
      acquireLease()
      expectHeartBeat()
      granted.get() shouldEqual true

      heartBeatFailure()
    }

    "call lease lost callback immediately on heartbeat failure" in new NoRetryTest {
      @volatile var callbackCalled: Option[Throwable] = None
      acquireLease(reason => callbackCalled = reason)
      expectHeartBeat()
      granted.get() shouldEqual true

      failAllHeartbeatAttempts(new LeaseException("Failed to communicate with API server"))
      awaitAssert {
        callbackCalled shouldBe defined
      }
    }

    "allow re-acquire after immediate heartbeat failure" in new NoRetryTest {
      acquireLease()
      expectHeartBeat()
      heartBeatFailure()
      acquireLease()
    }

    "immediately report release failure with no retries" in new NoRetryTest {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      acquireLease()
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))
      updateProbe.reply(Failure(k8sApiFailure))
      senderProbe.expectMsg(Failure(k8sApiFailure))
    }

    "allow re-acquire after immediate release failure" in new NoRetryTest {
      acquireLease()
      underTest ! Release()
      updateProbe.expectMsg(("", currentVersion))
      updateProbe.reply(Failure(new LeaseException("Failed")))
      senderProbe.expectMsgType[Failure]
      acquireLease()
    }

  }

  trait ShortOperationTimeoutTest extends Test {
    // an operation timeout that is already spent by the time the first conflict is handled
    override def timeoutSettings: TimeoutSettings = new TimeoutSettings(25.millis, 250.millis, 1.nano)
  }

  trait ReleaseRetryTimingTest extends Test {
    // heartbeat-interval is deliberately far larger than the operation timeout: if release retries
    // were paced off the heartbeat interval they would be 5s apart and the probe would never see them
    override def timeoutSettings: TimeoutSettings = new TimeoutSettings(20.seconds, 60.seconds, 1.second)
  }

  trait IndependentRetryCountsTest extends Test {
    override def heartbeatMaxRetries: Int = 0
    override def releaseMaxRetries: Int = 2
  }

  "LeaseActor retry settings" should {

    "apply heartbeat-max-retries and release-max-retries independently" in new IndependentRetryCountsTest {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      acquireLease()
      expectHeartBeat()

      // heartbeat-max-retries is 0, so the lease is given up on the first failed heartbeat
      updateProbe.expectMsg((ownerName, currentVersion))
      updateProbe.reply(Failure(k8sApiFailure))
      awaitAssert {
        granted.get() shouldEqual false
      }

      // release-max-retries is 2, so the release is attempted three times before the caller is told
      acquireLease()
      underTest ! Release()
      failAllReleaseAttempts(k8sApiFailure)
      senderProbe.expectMsg(Failure(k8sApiFailure))
    }

  }

  "LeaseActor acquire conflict retry" should {

    "reply LeaseAcquired only once the retry succeeds" in new Test {
      acquireLease()
      releaseLease()

      // Start acquire, will hit a conflict and retry
      underTest ! LeaseActor.Acquire()
      updateProbe.expectMsg((ownerName, currentVersion))
      // Conflict: version moved on, no owner
      val conflictVersion = currentVersionCount + otherClientUpdates
      updateProbe.reply(Left(LeaseResource(None, conflictVersion.toString, System.currentTimeMillis())))
      // Nothing is reported to the caller until the retry has been answered
      senderProbe.expectNoMessage(100.millis)
      // Retry uses the version from the conflict response, and success moves the version on again
      updateProbe.expectMsg((ownerName, conflictVersion.toString))
      currentVersionCount = conflictVersion + 1
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
      granted.get() shouldEqual true
    }

    "keep retrying while the version keeps moving on" in new Test {
      acquireLease()
      releaseLease()

      underTest ! LeaseActor.Acquire()
      updateProbe.expectMsg((ownerName, currentVersion))
      // the version moves on again between the first conflict and the retry landing
      val firstConflict = currentVersionCount + otherClientUpdates
      updateProbe.reply(Left(LeaseResource(None, firstConflict.toString, System.currentTimeMillis())))
      updateProbe.expectMsg((ownerName, firstConflict.toString))
      val secondConflict = firstConflict + otherClientUpdates
      updateProbe.reply(Left(LeaseResource(None, secondConflict.toString, System.currentTimeMillis())))

      // the third attempt uses the version from the second conflict, not the original one
      updateProbe.expectMsg((ownerName, secondConflict.toString))
      currentVersionCount = secondConflict + 1
      updateProbe.reply(Right(LeaseResource(Some(ownerName), currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseAcquired)
      granted.get() shouldEqual true
    }

    "reply LeaseTaken if another owner has the lease by the time the retry lands" in new Test {
      acquireLease()
      releaseLease()

      underTest ! LeaseActor.Acquire()
      updateProbe.expectMsg((ownerName, currentVersion))
      val conflictVersion = currentVersionCount + otherClientUpdates
      updateProbe.reply(Left(LeaseResource(None, conflictVersion.toString, System.currentTimeMillis())))

      updateProbe.expectMsg((ownerName, conflictVersion.toString))
      updateProbe.reply(
        Left(LeaseResource(Some("i got there first"), (conflictVersion + 1).toString, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseTaken)
      granted.get() shouldEqual false
    }

    "give up and fail the caller once the lease operation timeout is spent" in new ShortOperationTimeoutTest {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      // version has moved on but the lease is not taken, so this would normally be retried
      updateProbe.reply(Left(LeaseResource(None, currentVersion, System.currentTimeMillis())))

      senderProbe.expectMsgType[Failure].cause shouldBe a[LeaseTimeoutException]
      // no further retry is issued, the lease is not granted behind the caller's back
      updateProbe.expectNoMessage(200.millis)
      granted.get() shouldEqual false
    }

    "be able to acquire again after giving up on conflict retries" in new ShortOperationTimeoutTest {
      underTest ! LeaseActor.Acquire()
      leaseProbe.expectMsg(leaseName)
      leaseProbe.reply(LeaseResource(None, currentVersion, System.currentTimeMillis()))
      updateProbe.expectMsg((ownerName, currentVersion))
      incrementVersion()
      updateProbe.reply(Left(LeaseResource(None, currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsgType[Failure].cause shouldBe a[LeaseTimeoutException]

      acquireLease()
    }

  }

  "LeaseActor release retry" should {

    "pace retries off the lease operation timeout, not the heartbeat interval" in new ReleaseRetryTimingTest {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      acquireLease()
      val operationTimeout = leaseSettings.timeoutSettings.operationTimeout
      val start = System.nanoTime()
      // Paced off the operation timeout these attempts are 250ms apart; paced off the heartbeat
      // interval they would be 5s apart and the probe would time out waiting for them.
      underTest ! Release()
      failAllReleaseAttempts(k8sApiFailure)
      senderProbe.expectMsg(Failure(k8sApiFailure))
      // the caller is told the outcome before the ask it is waiting on would have timed out
      (System.nanoTime() - start).nanos should be < operationTimeout.dilated
    }

    "retry release on failure and succeed" in new Test {
      acquireLease()
      underTest ! Release()
      // First attempt fails
      updateProbe.expectMsg(("", currentVersion))
      updateProbe.reply(Failure(new LeaseException("transient error")))
      // Retry succeeds
      updateProbe.expectMsg(("", currentVersion))
      incrementVersion()
      updateProbe.reply(Right(LeaseResource(None, currentVersion, System.currentTimeMillis())))
      senderProbe.expectMsg(LeaseReleased)
    }

    "report release failure after retries exhausted" in new Test {
      val k8sApiFailure = new LeaseException("Failed to communicate with API server")
      acquireLease()
      underTest ! Release()
      failAllReleaseAttempts(k8sApiFailure)
      senderProbe.expectMsg(Failure(k8sApiFailure))
    }

  }
}
