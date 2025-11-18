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

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.InternalApi
import pekko.coordination.lease.TimeoutSettings
import com.typesafe.config.Config

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.jdk.DurationConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object KubernetesSettings {

  private implicit class HasDefined(val config: Config) {
    def hasDefined(key: String): Boolean =
      config.hasPath(key) &&
      config.getString(key).trim.nonEmpty &&
      config.getString(key) != s"<$key>"

    def optDefinedValue(key: String): Option[String] =
      if (hasDefined(key)) Some(config.getString(key)) else None
  }

  def apply(system: ActorSystem, leaseTimeoutSettings: TimeoutSettings): KubernetesSettings = {
    apply(system.settings.config.getConfig(AbstractKubernetesLease.configPath), leaseTimeoutSettings)
  }
  def apply(config: Config, leaseTimeoutSettings: TimeoutSettings): KubernetesSettings = {

    val apiServerRequestTimeout =
      if (config.hasDefined("api-server-request-timeout"))
        config.getDuration("api-server-request-timeout").toScala
      else
        leaseTimeoutSettings.operationTimeout * 2 / 5 // 2/5 gives two API operations + a buffer

    require(
      apiServerRequestTimeout < leaseTimeoutSettings.operationTimeout,
      "'api-server-request-timeout can not be less than 'lease-operation-timeout'")

    new KubernetesSettings(
      config.getString("api-ca-path"),
      config.getString("api-token-path"),
      config.getString("api-service-host"),
      config.getInt("api-service-port"),
      config.optDefinedValue("namespace"),
      config.getString("namespace-path"),
      apiServerRequestTimeout,
      secure = config.getBoolean("secure-api-server"),
      tlsVersion = config.getString("tls-version"),
      bodyReadTimeout = apiServerRequestTimeout / 2)

  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] class KubernetesSettings(
    val apiCaPath: String,
    val apiTokenPath: String,
    val apiServerHost: String,
    val apiServerPort: Int,
    val namespace: Option[String],
    val namespacePath: String,
    val apiServerRequestTimeout: FiniteDuration,
    val secure: Boolean = true,
    val tlsVersion: String = "TLSv1.3",
    val bodyReadTimeout: FiniteDuration = 1.second)
