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

package org.apache.pekko.discovery.consul

import org.apache.pekko
import pekko.actor.ClassicActorSystemProvider
import pekko.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import pekko.annotation.ApiMayChange
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

@ApiMayChange
final class ConsulSettings(system: ExtendedActorSystem) extends Extension {
  private val consulConfig = system.settings.config.getConfig("pekko.discovery.pekko-consul")

  val consulHost: String = consulConfig.getString("consul-host")

  val consulPort: Int = consulConfig.getInt("consul-port")

  val applicationNameTagPrefix: String = consulConfig.getString("application-name-tag-prefix")
  val applicationPekkoManagementPortTagPrefix: String =
    consulConfig.getString("application-pekko-management-port-tag-prefix")

  val connectTimeout: FiniteDuration =
    consulConfig.getDuration("connect-timeout", TimeUnit.MILLISECONDS).millis

  val readTimeout: FiniteDuration =
    consulConfig.getDuration("read-timeout", TimeUnit.MILLISECONDS).millis

  val writeTimeout: FiniteDuration =
    consulConfig.getDuration("write-timeout", TimeUnit.MILLISECONDS).millis

  val parallelism: Int = consulConfig.getInt("lookup-parallelism")
}

@ApiMayChange
object ConsulSettings extends ExtensionId[ConsulSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): ConsulSettings = super.get(system)

  override def get(system: ClassicActorSystemProvider): ConsulSettings = super.get(system)

  override def lookup: ConsulSettings.type = ConsulSettings

  override def createExtension(system: ExtendedActorSystem): ConsulSettings = new ConsulSettings(system)
}
