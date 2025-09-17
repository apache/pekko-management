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

import java.net.InetAddress
import java.util.Optional

import scala.collection.immutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import org.apache.pekko
import pekko.annotation.InternalApi

import com.typesafe.config.Config

final class PekkoManagementSettings(val config: Config) {
  private val managementConfig = config.getConfig("pekko.management")

  object Http {
    private val cc = managementConfig.getConfig("http")

    val Hostname: String = {
      val hostname = cc.getString("hostname")
      if (hostname == "<hostname>") InetAddress.getLocalHost.getHostAddress
      else if (hostname.trim() == "") InetAddress.getLocalHost.getHostAddress
      else hostname
    }

    val Port: Int = {
      val p = cc.getInt("port")
      require(0 to 65535 contains p, s"pekko.management.http.port must be 0 through 65535 (was $p)")
      p
    }

    val EffectiveBindHostname: String = cc.getString("bind-hostname") match {
      case ""    => Hostname
      case value => value
    }

    val EffectiveBindPort: Int = cc.getString("bind-port") match {
      case "" => Port
      case value =>
        val p = value.toInt
        require(0 to 65535 contains p, s"pekko.management.http.bind-port must be 0 through 65535 (was $p)")
        p
    }

    val BasePath: Option[String] =
      Option(cc.getString("base-path")).flatMap(it => if (it.trim == "") None else Some(it))

    val RouteProviders: immutable.Seq[NamedRouteProvider] = {
      def validFQCN(value: Any) = {
        value != null &&
        value != "null" &&
        value.toString.trim.nonEmpty
      }

      cc.getConfig("routes")
        .root
        .unwrapped
        .asScala
        .collect {
          case (name, value) if validFQCN(value) => NamedRouteProvider(name, value.toString)
        }
        .toList
    }

    val RouteProvidersReadOnly: Boolean = cc.getBoolean("route-providers-read-only")
  }

  /** Java API */
  def getHttpHostname: String = Http.Hostname

  /** Java API */
  def getHttpPort: Int = Http.Port

  /** Java API */
  def getHttpEffectiveBindHostname: String = Http.EffectiveBindHostname

  /** Java API */
  def getHttpEffectiveBindPort: Int = Http.EffectiveBindPort

  /** Java API */
  def getBasePath: Optional[String] = Http.BasePath.toJava

  /** Java API */
  def getHttpRouteProviders: java.util.List[NamedRouteProvider] = Http.RouteProviders.asJava

}

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object PekkoManagementSettings {

  implicit class HasDefined(val config: Config) {
    def hasDefined(key: String): Boolean =
      config.hasPath(key) &&
      config.getString(key).trim.nonEmpty &&
      config.getString(key) != s"<$key>"

    def optDefinedValue(key: String): Option[String] =
      if (hasDefined(key)) Some(config.getString(key)) else None

    def optValue(key: String): Option[String] =
      config.getString(key) match {
        case ""    => None
        case other => Some(other)
      }
  }
}

final case class NamedRouteProvider(name: String, fullyQualifiedClassName: String)
