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

package org.apache.pekko.management

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.setup.ActorSystemSetup
import org.apache.pekko.actor.{ ActorSystem, BootstrapSetup, ExtendedActorSystem }
import org.apache.pekko.management.HealthChecksSpec.{ ctxException, failedCause }
import org.apache.pekko.management.internal.{ CheckFailedException, CheckTimeoutException }
import org.apache.pekko.management.scaladsl.{ HealthChecks, LivenessCheckSetup, ReadinessCheckSetup }
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.{ immutable => im }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NoStackTrace

object HealthChecksSpec {
  val failedCause = new TE()
  val ctxException = new TE()
}

class TE extends RuntimeException with NoStackTrace

class Ok(system: ActorSystem) extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    Future.successful(true)
  }
}

class False(system: ActorSystem) extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    Future.successful(false)
  }
}
class Throws(system: ActorSystem) extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    Future.failed(failedCause)
  }
}

class NoArgsCtr() extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    Future.successful(true)
  }
}

class InvalidCtr(cat: String) extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    Future.successful(true)
  }
}

class Slow(system: ActorSystem) extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      Thread.sleep(20000)
      false
    }
  }
}

class Naughty() extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = {
    throw new RuntimeException("bad")
  }
}

class WrongType() {}

class CtrException(system: ActorSystem) extends (() => Future[Boolean]) {
  override def apply(): Future[Boolean] = ???
  if (System.currentTimeMillis() != -1) throw ctxException // avoid compiler warning
}

class HealthChecksSpec
    extends TestKit(ActorSystem("HealthChecksSpec"))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with ScalaFutures
    with Matchers {

  val eas = system.asInstanceOf[ExtendedActorSystem]

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val OkCheck = NamedHealthCheck("Ok", "org.apache.pekko.management.Ok")
  val FalseCheck = NamedHealthCheck("False", "org.apache.pekko.management.False")
  val ThrowsCheck = NamedHealthCheck("Throws", "org.apache.pekko.management.Throws")
  val SlowCheck = NamedHealthCheck("Slow", "org.apache.pekko.management.Slow")
  val NoArgsCtrCheck = NamedHealthCheck("NoArgsCtr", "org.apache.pekko.management.NoArgsCtr")
  val NaughtyCheck = NamedHealthCheck("Naughty", "org.apache.pekko.management.Naughty")
  val InvalidCtrCheck = NamedHealthCheck("InvalidCtr", "org.apache.pekko.management.InvalidCtr")
  val WrongTypeCheck = NamedHealthCheck("WrongType", "org.apache.pekko.management.WrongType")
  val DoesNotExist = NamedHealthCheck("DoesNotExist", "org.apache.pekko.management.DoesNotExist")
  val CtrExceptionCheck = NamedHealthCheck("CtrExceptionCheck", "org.apache.pekko.management.CtrException")

  def settings(readiness: im.Seq[NamedHealthCheck], liveness: im.Seq[NamedHealthCheck]) =
    new HealthCheckSettings(readiness, liveness, "ready", "alive", 500.millis)

  "HealthCheck" should {
    "succeed by default" in {
      val checks = HealthChecks(eas, settings(Nil, Nil))
      checks.aliveResult().futureValue shouldEqual Right(())
      checks.readyResult().futureValue shouldEqual Right(())
      checks.alive().futureValue shouldEqual true
      checks.ready().futureValue shouldEqual true
    }
    "succeed for all health checks returning Right" in {
      val checks = HealthChecks(
        eas,
        settings(
          im.Seq(OkCheck),
          im.Seq(OkCheck)))
      checks.aliveResult().futureValue shouldEqual Right(())
      checks.readyResult().futureValue shouldEqual Right(())
      checks.alive().futureValue shouldEqual true
      checks.ready().futureValue shouldEqual true
    }
    "support no args constructor" in {
      val checks = HealthChecks(
        eas,
        settings(
          im.Seq(NoArgsCtrCheck),
          im.Seq(NoArgsCtrCheck)))
      checks.aliveResult().futureValue shouldEqual Right(())
      checks.readyResult().futureValue shouldEqual Right(())
      checks.alive().futureValue shouldEqual true
      checks.ready().futureValue shouldEqual true
    }
    "fail for health checks returning Left" in {
      val checks = HealthChecks(
        eas,
        settings(
          im.Seq(FalseCheck),
          im.Seq(FalseCheck)))
      checks.readyResult().futureValue.isRight shouldEqual false
      checks.aliveResult().futureValue.isRight shouldEqual false
      checks.ready().futureValue shouldEqual false
      checks.alive().futureValue shouldEqual false
    }
    "return failure for all health checks fail" in {
      val checks = HealthChecks(
        eas,
        settings(
          im.Seq(ThrowsCheck),
          im.Seq(ThrowsCheck)))
      checks.readyResult().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
      checks.aliveResult().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
      checks.ready().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
      checks.alive().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
    }
    "return failure if any of the checks fail" in {
      val checks = im.Seq(
        OkCheck,
        ThrowsCheck,
        FalseCheck)
      val hc = HealthChecks(eas, settings(checks, checks))
      hc.readyResult().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
      hc.aliveResult().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
      hc.ready().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
      hc.alive().failed.futureValue shouldEqual
      CheckFailedException("Check [org.apache.pekko.management.Throws] failed: null", failedCause)
    }
    "return failure if check throws" in {
      val checks = im.Seq(
        NaughtyCheck)
      val hc = HealthChecks(eas, settings(checks, checks))
      hc.readyResult().failed.futureValue.getMessage shouldEqual "Check [org.apache.pekko.management.Naughty] failed: bad"
      hc.aliveResult().failed.futureValue.getMessage shouldEqual "Check [org.apache.pekko.management.Naughty] failed: bad"
      hc.ready().failed.futureValue.getMessage shouldEqual "Check [org.apache.pekko.management.Naughty] failed: bad"
      hc.alive().failed.futureValue.getMessage shouldEqual "Check [org.apache.pekko.management.Naughty] failed: bad"
    }
    "return failure if checks timeout" in {
      val checks = im.Seq(
        SlowCheck,
        OkCheck)
      val hc = HealthChecks(eas, settings(checks, checks))
      Await.result(hc.readyResult().failed, 1.second) shouldEqual CheckTimeoutException(
        "Check [org.apache.pekko.management.Slow] timed out after 500 milliseconds")
      Await.result(hc.aliveResult().failed, 1.second) shouldEqual CheckTimeoutException(
        "Check [org.apache.pekko.management.Slow] timed out after 500 milliseconds")
      Await.result(hc.ready().failed, 1.second) shouldEqual CheckTimeoutException(
        "Check [org.apache.pekko.management.Slow] timed out after 500 milliseconds")
      Await.result(hc.alive().failed, 1.second) shouldEqual CheckTimeoutException(
        "Check [org.apache.pekko.management.Slow] timed out after 500 milliseconds")
    }
    "provide useful error if user's ctr is invalid" in {
      intercept[InvalidHealthCheckException] {
        val checks = im.Seq(InvalidCtrCheck)
        HealthChecks(eas, settings(checks, checks))
      }.getMessage shouldEqual "Health checks: [NamedHealthCheck(InvalidCtr,org.apache.pekko.management.InvalidCtr)] must have a no args constructor or a single argument constructor that takes an ActorSystem"
    }
    "provide useful error if invalid type" in {
      intercept[InvalidHealthCheckException] {
        val checks = im.Seq(WrongTypeCheck)
        HealthChecks(eas, settings(checks, checks))
      }.getMessage shouldEqual "Health checks: [NamedHealthCheck(WrongType,org.apache.pekko.management.WrongType)] must have type: () => Future[Boolean]"
    }
    "provide useful error if class not found" in {
      intercept[InvalidHealthCheckException] {
        val checks =
          im.Seq(DoesNotExist, OkCheck)
        HealthChecks(eas, settings(checks, checks))
      }.getMessage shouldEqual "Health check: [org.apache.pekko.management.DoesNotExist] not found"
    }
    "provide useful error if class ctr throws" in {
      intercept[InvalidHealthCheckException] {
        val checks =
          im.Seq(OkCheck, CtrExceptionCheck)
        HealthChecks(eas, settings(checks, checks))
      }.getCause shouldEqual ctxException
    }
    "be possible to define via ActorSystem Setup" in {
      val readinessSetup = ReadinessCheckSetup(system => List(new Ok(system), new False(system)))
      val livenessSetup = LivenessCheckSetup(system => List(new False(system)))
      // bootstrapSetup is needed for config (otherwise default config)
      val bootstrapSetup = BootstrapSetup(ConfigFactory.parseString("some=thing"))
      val actorSystemSetup = ActorSystemSetup(bootstrapSetup, readinessSetup, livenessSetup)
      val sys2 = ActorSystem("HealthCheckSpec2", actorSystemSetup).asInstanceOf[ExtendedActorSystem]
      try {
        val checks = HealthChecks(
          sys2,
          settings(Nil, Nil) // no checks from config
        )
        checks.aliveResult().futureValue shouldEqual Left("Check [org.apache.pekko.management.False] not ok")
        checks.readyResult().futureValue shouldEqual Left("Check [org.apache.pekko.management.False] not ok")
        checks.alive().futureValue shouldEqual false
        checks.ready().futureValue shouldEqual false
      } finally {
        TestKit.shutdownActorSystem(sys2)
      }
    }
  }
}
