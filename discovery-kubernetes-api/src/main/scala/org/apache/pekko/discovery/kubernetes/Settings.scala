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

import com.typesafe.config.Config
import org.apache.pekko.actor._
import org.apache.pekko.util.OptionConverters._

import java.util.Optional

final class Settings(configNameSpace: Option[String], system: ExtendedActorSystem) extends Extension {

  def this(system: ExtendedActorSystem) = this(None, system)

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

  private val customSettings = configNameSpace.map(system.settings.config.getConfig)

  private val kubernetesApi =
    system.settings.config.getConfig("pekko.discovery.kubernetes-api")

  private def getString(path: String) = {
    customSettings match {
      case Some(customConfig) if customConfig.hasDefined(path) =>
        customConfig.getString(path)
      case _ => kubernetesApi.getString(path)
    }
  }

  private def getBoolean(path: String) = {
    customSettings match {
      case Some(customConfig) if customConfig.hasDefined(path) =>
        customConfig.getBoolean(path)
      case _ => kubernetesApi.getBoolean(path)
    }
  }

  private def getOptValue(config: String) =
    customSettings.fold(kubernetesApi.optDefinedValue(config))(_.optDefinedValue(config))

  val apiCaPath: String =
    getString("api-ca-path")

  val apiTokenPath: String =
    getString("api-token-path")

  val apiServiceHostEnvName: String =
    getString("api-service-host-env-name")

  val apiServicePortEnvName: String =
    getString("api-service-port-env-name")

  val podNamespacePath: String =
    getString("pod-namespace-path")

  /** Scala API */
  val podNamespace: Option[String] =
    getOptValue("pod-namespace")

  /** Java API */
  def getPodNamespace: Optional[String] = podNamespace.toJava

  val podDomain: String =
    getString("pod-domain")

  def podLabelSelector(name: String): String =
    getString("pod-label-selector").format(name)

  lazy val rawIp: Boolean = getBoolean("use-raw-ip")

  val containerName: Option[String] = Some(getString("container-name")).filter(_.nonEmpty)

  override def toString =
    s"Settings($apiCaPath, $apiTokenPath, $apiServiceHostEnvName, $apiServicePortEnvName, " +
    s"$podNamespacePath, $podNamespace, $podDomain)"
}

object Settings extends ExtensionId[Settings] with ExtensionIdProvider {
  override def get(system: ActorSystem): Settings = super.get(system)

  override def get(system: ClassicActorSystemProvider): Settings = super.get(system)

  override def lookup: Settings.type = Settings

  override def createExtension(system: ExtendedActorSystem): Settings = new Settings(system)
}
