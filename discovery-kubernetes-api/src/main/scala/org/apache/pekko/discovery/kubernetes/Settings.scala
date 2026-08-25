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

package org.apache.pekko.discovery.kubernetes

import java.util.Optional

import scala.concurrent.duration._
import scala.jdk.DurationConverters._
import scala.jdk.OptionConverters._

import org.apache.pekko.actor._
import com.typesafe.config.Config

final class Settings(kubernetesApi: Config) extends Extension {

  def this(system: ExtendedActorSystem) = this(system.settings.config.getConfig("pekko.discovery.kubernetes-api"))

  /**
   * Copied from PekkoManagementSettings, which we don't depend on.
   */
  private implicit class HasDefined(val config: Config) {
    def hasDefined(key: String): Boolean =
      config.hasPath(key) &&
      config.getString(key).trim.nonEmpty &&
      config.getString(key) != s"<$key>"

    def optDefinedValue(key: String): Option[String] =
      if (hasDefined(key)) Some(config.getString(key)) else None
  }

  val apiCaPath: String =
    kubernetesApi.getString("api-ca-path")

  val apiTokenPath: String =
    kubernetesApi.getString("api-token-path")

  val apiServiceHostEnvName: String =
    kubernetesApi.getString("api-service-host-env-name")

  val apiServicePortEnvName: String =
    kubernetesApi.getString("api-service-port-env-name")

  val tlsVersion: String =
    kubernetesApi.getString("tls-version")

  val podNamespacePath: String =
    kubernetesApi.getString("pod-namespace-path")

  /** Scala API */
  val podNamespace: Option[String] =
    kubernetesApi.optDefinedValue("pod-namespace")

  /** Java API */
  def getPodNamespace: Optional[String] = podNamespace.toJava

  val podDomain: String =
    kubernetesApi.getString("pod-domain")

  def podLabelSelector(name: String): String =
    kubernetesApi.getString("pod-label-selector").format(name)

  lazy val rawIp: Boolean = kubernetesApi.getBoolean("use-raw-ip")

  val containerName: Option[String] = Some(kubernetesApi.getString("container-name")).filter(_.nonEmpty)

  val httpRequestAcceptEncoding: String = kubernetesApi.getString("http-request-accept-encoding")

  /** The API poll mode: "list" for repeated GET requests (default), "watch" for Kubernetes Watch API streaming. */
  val apiPollMode: String = {
    val configured = kubernetesApi.getString("api-poll-mode")
    require(
      configured == Settings.ListPollMode || configured == Settings.WatchPollMode,
      s"pekko.discovery.kubernetes-api.api-poll-mode must be " +
      s"'${Settings.ListPollMode}' or '${Settings.WatchPollMode}', was [$configured]")
    configured
  }

  /** Whether the Kubernetes Watch API is used instead of repeated pod list requests. */
  val watchMode: Boolean = apiPollMode == Settings.WatchPollMode

  /** Delay before reconnecting the watch stream after a normal stream completion. */
  val watchReconnectDelay: FiniteDuration =
    kubernetesApi.getDuration("watch-reconnect-delay").toScala

  /** Delay before reconnecting the watch stream after an error. */
  val watchOnErrorReconnectDelay: FiniteDuration =
    kubernetesApi.getDuration("watch-on-error-reconnect-delay").toScala

  /** Maximum size of a single newline-delimited event in the watch stream. */
  val watchMaxFrameLength: Int = {
    val configured = kubernetesApi.getBytes("watch-max-frame-length")
    require(
      configured > 0 && configured <= Int.MaxValue,
      s"pekko.discovery.kubernetes-api.watch-max-frame-length must be between 1 and ${Int.MaxValue} bytes, " +
      s"was [$configured]")
    configured.toInt
  }

  override def toString =
    s"Settings($apiCaPath, $apiTokenPath, $apiServiceHostEnvName, $apiServicePortEnvName, " +
    s"$podNamespacePath, $podNamespace, $podDomain, httpRequestAcceptEncoding=$httpRequestAcceptEncoding, " +
    s"apiPollMode=$apiPollMode)"
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {

  /** Poll the Kubernetes API with a full pod list request on every lookup. */
  val ListPollMode = "list"

  /** Track pods with a long-lived Kubernetes Watch API stream. */
  val WatchPollMode = "watch"

  override def get(system: ActorSystem): Settings = super.get(system)

  override def get(system: ClassicActorSystemProvider): Settings = super.get(system)

  override def lookup: Settings.type = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = new Settings(system)
}
