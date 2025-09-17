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
import java.util.function.{ Function => JFunction }

import org.apache.pekko
import pekko.Done
import pekko.actor.{ ActorSystem, ClassicActorSystemProvider }
import pekko.http.javadsl.model.Uri
import pekko.http.javadsl.server.directives.RouteAdapter
import pekko.management.PekkoManagementSettings
import pekko.management.scaladsl

import scala.jdk.FutureConverters._

object PekkoManagement {
  def get(system: ActorSystem): PekkoManagement =
    new PekkoManagement(scaladsl.PekkoManagement(system))

  def get(classicActorSystemProvider: ClassicActorSystemProvider): PekkoManagement =
    new PekkoManagement(scaladsl.PekkoManagement(classicActorSystemProvider))
}

final class PekkoManagement(delegate: scaladsl.PekkoManagement) {

  def settings: PekkoManagementSettings = delegate.settings

  /**
   * Get the routes for the HTTP management endpoint.
   *
   * This method can be used to embed the Pekko management routes in an existing Pekko HTTP server.
   * @throws java.lang.IllegalArgumentException if routes not configured for pekko management
   */
  def getRoutes: pekko.http.javadsl.server.Route =
    RouteAdapter(delegate.routes)

  /**
   * Amend the [[ManagementRouteProviderSettings]] and get the routes for the HTTP management endpoint.
   *
   * Use this when adding authentication and HTTPS.
   *
   * This method can be used to embed the Pekko management routes in an existing Pekko HTTP server.
   * @throws java.lang.IllegalArgumentException if routes not configured for pekko management
   */
  def getRoutes(transformSettings: JFunction[ManagementRouteProviderSettings, ManagementRouteProviderSettings])
      : pekko.http.javadsl.server.Route =
    RouteAdapter(delegate.routes(convertSettingsTransformation(transformSettings)))

  private def convertSettingsTransformation(
      transformSettings: JFunction[ManagementRouteProviderSettings, ManagementRouteProviderSettings])
      : scaladsl.ManagementRouteProviderSettings => scaladsl.ManagementRouteProviderSettings = { scaladslSettings =>
    {
      val scaladslSettingsImpl = scaladslSettings.asInstanceOf[scaladsl.ManagementRouteProviderSettingsImpl]
      val javadslTransformedSettings = transformSettings.apply(scaladslSettingsImpl.asJava)
      val javadslTransformedSettingsImpl = javadslTransformedSettings.asInstanceOf[ManagementRouteProviderSettingsImpl]
      javadslTransformedSettingsImpl.asScala
    }
  }

  /**
   * Start a Pekko HTTP server to serve the HTTP management endpoint.
   */
  def start(): CompletionStage[Uri] =
    delegate.start().map(Uri.create)(delegate.system.dispatcher).asJava

  /**
   * Start a Pekko HTTP server to serve the HTTP management endpoint.
   */
  def start(transformSettings: JFunction[ManagementRouteProviderSettings, ManagementRouteProviderSettings])
      : CompletionStage[Uri] =
    delegate.start(convertSettingsTransformation(transformSettings)).map(Uri.create)(delegate.system.dispatcher).asJava

  def stop(): CompletionStage[Done] =
    delegate.stop().asJava

}
