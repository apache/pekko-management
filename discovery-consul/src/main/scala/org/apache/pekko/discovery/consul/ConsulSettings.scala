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

  /**
   * Connection timeout for the Consul HTTP client.
   * @since 2.0.0
   */
  val connectTimeout: FiniteDuration =
    consulConfig.getDuration("connect-timeout").toScala

  /**
   * Read timeout for the Consul HTTP client.
   * @since 2.0.0
   */
  val readTimeout: FiniteDuration =
    consulConfig.getDuration("read-timeout").toScala

  /**
   * Write timeout for the Consul HTTP client.
   * @since 2.0.0
   */
  val writeTimeout: FiniteDuration =
    consulConfig.getDuration("write-timeout").toScala

  /**
   * Maximum number of concurrent requests when looking up multiple service IDs.
   * Must be greater than 0.
   * @since 2.0.0
   */
  val parallelism: Int = {
    val configured = consulConfig.getInt("lookup-parallelism")
    require(
      configured > 0,
      s"pekko.discovery.pekko-consul.lookup-parallelism must be greater than 0, was [$configured]")
    configured
  }

  /**
   * ACL token for Consul API authentication. Empty means no authentication.
   * @since 2.0.0
   */
  val consulToken: Option[String] = consulConfig.getString("consul-token") match {
    case ""  => None
    case tok => Some(tok)
  }

  /**
   * Whether to use HTTPS when connecting to Consul.
   * @since 2.0.0
   */
  val tlsEnabled: Boolean = consulConfig.getBoolean("tls-enabled")

  /**
   * Path to a PEM-encoded CA certificate file for TLS verification.
   * Only used when tls-enabled = true. If empty, the default JVM trust store is used.
   * @since 2.0.0
   */
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
