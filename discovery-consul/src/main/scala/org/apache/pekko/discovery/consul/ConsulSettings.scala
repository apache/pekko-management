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
import scala.concurrent.duration._
import scala.jdk.DurationConverters._

@ApiMayChange
final class ConsulSettings(system: ExtendedActorSystem) extends Extension {
  private val consulConfig = system.settings.config.getConfig("pekko.discovery.pekko-consul")

  val consulHost: String = consulConfig.getString("consul-host")

  val consulPort: Int = consulConfig.getInt("consul-port")

  val applicationNameTagPrefix: String = consulConfig.getString("application-name-tag-prefix")
  val applicationPekkoManagementPortTagPrefix: String =
    consulConfig.getString("application-pekko-management-port-tag-prefix")

  val connectTimeout: FiniteDuration =
    consulConfig.getDuration("connect-timeout").toScala

  val readTimeout: FiniteDuration =
    consulConfig.getDuration("read-timeout").toScala

  val writeTimeout: FiniteDuration =
    consulConfig.getDuration("write-timeout").toScala

  val parallelism: Int = consulConfig.getInt("lookup-parallelism")

  val consulToken: Option[String] = consulConfig.getString("consul-token") match {
    case ""  => None
    case tok => Some(tok)
  }

  val tlsEnabled: Boolean = consulConfig.getBoolean("tls-enabled")

  val caPath: Option[String] = consulConfig.getString("ca-path") match {
    case ""   => None
    case path => Some(path)
  }
}

@ApiMayChange
object ConsulSettings extends ExtensionId[ConsulSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): ConsulSettings = super.get(system)

  override def get(system: ClassicActorSystemProvider): ConsulSettings = super.get(system)

  override def lookup: ConsulSettings.type = ConsulSettings

  override def createExtension(system: ExtendedActorSystem): ConsulSettings = new ConsulSettings(system)
}
