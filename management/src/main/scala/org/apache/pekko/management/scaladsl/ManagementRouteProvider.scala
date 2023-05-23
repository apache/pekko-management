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

import org.apache.pekko
import pekko.actor.Extension
import pekko.annotation.InternalApi
import pekko.http.scaladsl.server.Route
import pekko.management.javadsl

/** Extend this trait in your extension in order to allow it to contribute routes to Pekko Management starts its HTTP endpoint */
trait ManagementRouteProvider extends Extension {

  /** Routes to be exposed by Pekko cluster management */
  def routes(settings: ManagementRouteProviderSettings): Route

}

/**
 * INTERNAL API
 */
@InternalApi private[pekko] final class ManagementRouteProviderAdapter(delegate: javadsl.ManagementRouteProvider)
    extends ManagementRouteProvider {
  override def routes(settings: ManagementRouteProviderSettings): Route = {
    val settingsImpl = settings.asInstanceOf[ManagementRouteProviderSettingsImpl]
    delegate.routes(settingsImpl.asJava).asScala
  }

}
