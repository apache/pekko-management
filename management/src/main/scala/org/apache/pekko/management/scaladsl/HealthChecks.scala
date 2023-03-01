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

package org.apache.pekko.management.scaladsl
import scala.collection.immutable
import scala.concurrent.Future
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.actor.ExtendedActorSystem
import pekko.actor.setup.Setup
import pekko.annotation.DoNotInherit
import pekko.management.HealthCheckSettings
import pekko.management.internal.HealthChecksImpl

/**
 * Loads health checks from configuration and ActorSystem Setup
 */
object HealthChecks {
  def apply(system: ExtendedActorSystem, settings: HealthCheckSettings): HealthChecks =
    new HealthChecksImpl(system, settings)

  type HealthCheck = () => Future[Boolean]

}

/**
 * Not for user extension
 */
@DoNotInherit
abstract class HealthChecks {

  /**
   * Returns Future(true) if the system is ready to receive user traffic
   */
  def ready(): Future[Boolean]

  /**
   * Returns Future(result) containing the system's readiness result
   */
  def readyResult(): Future[Either[String, Unit]]

  /**
   * Returns Future(true) to indicate that the process is alive but does not
   * mean that it is ready to receive traffic e.g. is has not joined the cluster
   * or is loading initial state from a database
   */
  def alive(): Future[Boolean]

  /**
   * Returns Future(result) containing the system's liveness result
   */
  def aliveResult(): Future[Either[String, Unit]]
}

object ReadinessCheckSetup {

  /**
   * Programmatic definition of readiness checks
   */
  def apply(createHealthChecks: ActorSystem => immutable.Seq[HealthChecks.HealthCheck]): ReadinessCheckSetup = {
    new ReadinessCheckSetup(createHealthChecks)
  }

}

/**
 * Setup for readiness checks, constructor is *Internal API*, use factories in [[ReadinessCheckSetup]]
 */
final class ReadinessCheckSetup private (
    val createHealthChecks: ActorSystem => immutable.Seq[HealthChecks.HealthCheck]) extends Setup

object LivenessCheckSetup {

  /**
   * Programmatic definition of liveness checks
   */
  def apply(createHealthChecks: ActorSystem => immutable.Seq[HealthChecks.HealthCheck]): LivenessCheckSetup = {
    new LivenessCheckSetup(createHealthChecks)
  }

}

/**
 * Setup for liveness checks, constructor is *Internal API*, use factories in [[LivenessCheckSetup]]
 */
final class LivenessCheckSetup private (
    val createHealthChecks: ActorSystem => immutable.Seq[HealthChecks.HealthCheck]) extends Setup
