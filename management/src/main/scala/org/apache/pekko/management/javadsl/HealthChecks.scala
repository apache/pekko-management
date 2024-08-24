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

package org.apache.pekko.management.javadsl

import java.util.concurrent.CompletionStage
import java.util.function.Supplier
import java.util.function.{ Function => JFunction }
import java.util.{ List => JList, Optional }
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.actor.ExtendedActorSystem
import pekko.actor.setup.Setup
import pekko.management.HealthCheckSettings
import pekko.management.internal.HealthChecksImpl
import pekko.util.FunctionConverters._
import pekko.util.FutureConverters._

import scala.annotation.nowarn

/**
 * Can be used to instantiate health checks directly rather than rely on the
 * automatic management route. Useful if want to host the health check via
 * a protocol other than HTTP or not in the Pekko Management HTTP server
 */
final class HealthChecks(system: ExtendedActorSystem, settings: HealthCheckSettings) {

  private val delegate = new HealthChecksImpl(system, settings)

  /**
   * Returns CompletionStage(result), containing the system's startup result
   */
  def startupResult(): CompletionStage[CheckResult] =
    delegate.startupResult().map(new CheckResult(_))(system.dispatcher).asJava

  /**
   * Returns CompletionStage(true) if the system has started
   */
  def startup(): CompletionStage[java.lang.Boolean] =
    startupResult().thenApply(((r: CheckResult) => r.isSuccess).asJava)

  /**
   * Returns CompletionStage(result), containing the system's readiness result
   */
  def readyResult(): CompletionStage[CheckResult] =
    delegate.readyResult().map(new CheckResult(_))(system.dispatcher).asJava

  /**
   * Returns CompletionStage(true) if the system is ready to receive user traffic
   */
  def ready(): CompletionStage[java.lang.Boolean] =
    readyResult().thenApply(((r: CheckResult) => r.isSuccess).asJava)

  /**
   * Returns CompletionStage(true) to indicate that the process is alive but does not
   * mean that it is ready to receive traffic e.g. is has not joined the cluster
   * or is loading initial state from a database
   */
  def aliveResult(): CompletionStage[CheckResult] =
    delegate.aliveResult().map(new CheckResult(_))(system.dispatcher).asJava

  /**
   * Returns CompletionStage(result) containing the system's liveness result
   */
  def alive(): CompletionStage[java.lang.Boolean] =
    aliveResult().thenApply(((r: CheckResult) => r.isSuccess).asJava)
}

object StartupCheckSetup {

  /**
   * Programmatic definition of startup checks
   */
  def create(createHealthChecks: JFunction[ActorSystem, JList[Supplier[CompletionStage[java.lang.Boolean]]]])
      : StartupCheckSetup = {
    new StartupCheckSetup(createHealthChecks)
  }
}

/**
 * Setup for startup checks, constructor is *Internal API*, use factories in [[StartupCheckSetup]]
 */
final class StartupCheckSetup private (
    val createHealthChecks: JFunction[ActorSystem, JList[Supplier[CompletionStage[java.lang.Boolean]]]]) extends Setup

object ReadinessCheckSetup {

  /**
   * Programmatic definition of readiness checks
   */
  def create(createHealthChecks: JFunction[ActorSystem, JList[Supplier[CompletionStage[java.lang.Boolean]]]])
      : ReadinessCheckSetup = {
    new ReadinessCheckSetup(createHealthChecks)
  }

}

/**
 * Setup for readiness checks, constructor is *Internal API*, use factories in [[ReadinessCheckSetup]]
 */
final class ReadinessCheckSetup private (
    val createHealthChecks: JFunction[ActorSystem, JList[Supplier[CompletionStage[java.lang.Boolean]]]]) extends Setup

object LivenessCheckSetup {

  /**
   * Programmatic definition of liveness checks
   */
  def create(createHealthChecks: JFunction[ActorSystem, JList[Supplier[CompletionStage[java.lang.Boolean]]]])
      : LivenessCheckSetup = {
    new LivenessCheckSetup(createHealthChecks)
  }

}

/**
 * Setup for liveness checks, constructor is *Internal API*, use factories in [[LivenessCheckSetup]]
 */
final class LivenessCheckSetup private (
    val createHealthChecks: JFunction[ActorSystem, JList[Supplier[CompletionStage[java.lang.Boolean]]]]) extends Setup

/**
 * Result for readiness and liveness checks
 */
final class CheckResult private[javadsl] (private val result: Either[String, Unit]) {
  def failure: Optional[String] =
    Optional.ofNullable(result.left.toOption.orNull)

  def isFailure: java.lang.Boolean = result.isLeft

  def isSuccess: java.lang.Boolean = result.isRight

  @nowarn // remove annotation and ".right" with Scala 2.12 support
  def success: Optional[Unit] =
    Optional.ofNullable(result.right.toOption.orNull)
}
