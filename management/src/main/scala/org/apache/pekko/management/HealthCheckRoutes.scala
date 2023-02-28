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

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.annotation.InternalApi
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{ PathMatchers, Route }
import org.apache.pekko.management.scaladsl.{ HealthChecks, ManagementRouteProvider, ManagementRouteProviderSettings }

import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 *
 * We could make this public so users can add it to their own server, not sure
 * for ManagementRouteProviders
 */
@InternalApi
private[pekko] class HealthCheckRoutes(system: ExtendedActorSystem) extends ManagementRouteProvider {

  private val settings: HealthCheckSettings = HealthCheckSettings(
    system.settings.config.getConfig("pekko.management.health-checks"))

  // exposed for testing
  protected val healthChecks = HealthChecks(system, settings)

  private val healthCheckResponse: Try[Either[String, Unit]] => Route = {
    case Success(Right(())) => complete(StatusCodes.OK)
    case Success(Left(failingChecks)) =>
      complete(StatusCodes.InternalServerError -> s"Not Healthy: $failingChecks")
    case Failure(t) =>
      complete(
        StatusCodes.InternalServerError -> s"Health Check Failed: ${t.getMessage}")
  }

  override def routes(mrps: ManagementRouteProviderSettings): Route = {
    concat(
      path(PathMatchers.separateOnSlashes(settings.readinessPath)) {
        get {
          onComplete(healthChecks.readyResult())(healthCheckResponse)
        }
      },
      path(PathMatchers.separateOnSlashes(settings.livenessPath)) {
        get {
          onComplete(healthChecks.aliveResult())(healthCheckResponse)
        }
      })
  }
}
