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

import com.typesafe.config.Config
import org.apache.pekko
import pekko.util.JavaDurationConverters._
import pekko.util.ccompat.JavaConverters._

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

final case class NamedHealthCheck(name: String, fullyQualifiedClassName: String)

object HealthCheckSettings {
  def apply(config: Config): HealthCheckSettings = {
    def validFQCN(value: Any) = {
      value != null &&
      value != "null" &&
      value.toString.trim.nonEmpty
    }

    new HealthCheckSettings(
      config
        .getConfig("startup-checks")
        .root
        .unwrapped
        .asScala
        .collect {
          case (name, value) if validFQCN(value) => NamedHealthCheck(name, value.toString)
        }
        .toList,
      config
        .getConfig("readiness-checks")
        .root
        .unwrapped
        .asScala
        .collect {
          case (name, value) if validFQCN(value) => NamedHealthCheck(name, value.toString)
        }
        .toList,
      config
        .getConfig("liveness-checks")
        .root
        .unwrapped
        .asScala
        .collect {
          case (name, value) if validFQCN(value) => NamedHealthCheck(name, value.toString)
        }
        .toList,
      config.getString("startup-path"),
      config.getString("readiness-path"),
      config.getString("liveness-path"),
      config.getDuration("check-timeout").asScala)
  }

  /**
   * Java API
   */
  def create(config: Config): HealthCheckSettings = apply(config)

  /**
   * Java API
   */
  def create(
      startupChecks: java.util.List[NamedHealthCheck],
      readinessChecks: java.util.List[NamedHealthCheck],
      livenessChecks: java.util.List[NamedHealthCheck],
      startupPath: String,
      readinessPath: String,
      livenessPath: String,
      checkDuration: java.time.Duration) =
    new HealthCheckSettings(
      startupChecks.asScala.toList,
      readinessChecks.asScala.toList,
      livenessChecks.asScala.toList,
      startupPath,
      readinessPath,
      livenessPath,
      checkDuration.asScala)

  /**
   * Java API
   */
  @deprecated("Use create that takes `startupChecks` and `startupPath` parameters instead", "1.1.0")
  def create(
      readinessChecks: java.util.List[NamedHealthCheck],
      livenessChecks: java.util.List[NamedHealthCheck],
      readinessPath: String,
      livenessPath: String,
      checkDuration: java.time.Duration) =
    new HealthCheckSettings(
      Nil,
      readinessChecks.asScala.toList,
      livenessChecks.asScala.toList,
      "",
      readinessPath,
      livenessPath,
      checkDuration.asScala)
}

/**
 * @param startupChecks List of FQCN of startup checks
 * @param readinessChecks List of FQCN of readiness checks
 * @param livenessChecks List of FQCN of liveness checks
 * @param startupPath The path to serve startup on
 * @param readinessPath The path to serve readiness on
 * @param livenessPath The path to serve liveness on
 * @param checkTimeout how long to wait for all health checks to complete
 */
final class HealthCheckSettings(
    val startupChecks: immutable.Seq[NamedHealthCheck],
    val readinessChecks: immutable.Seq[NamedHealthCheck],
    val livenessChecks: immutable.Seq[NamedHealthCheck],
    val startupPath: String,
    val readinessPath: String,
    val livenessPath: String,
    val checkTimeout: FiniteDuration) {

  @deprecated("Use constructor that takes `startupChecks` and `startupPath` parameters instead", "1.1.0")
  def this(
      readinessChecks: immutable.Seq[NamedHealthCheck],
      livenessChecks: immutable.Seq[NamedHealthCheck],
      readinessPath: String,
      livenessPath: String,
      checkTimeout: FiniteDuration
  ) = {
    this(
      Nil,
      readinessChecks,
      livenessChecks,
      "",
      readinessPath,
      livenessPath,
      checkTimeout
    )
  }

  /**
   * Java API
   */
  def getStartupChecks(): java.util.List[NamedHealthCheck] = startupChecks.asJava

  /**
   * Java API
   */
  def getReadinessChecks(): java.util.List[NamedHealthCheck] = readinessChecks.asJava

  /**
   * Java API
   */
  def getLivenessChecks(): java.util.List[NamedHealthCheck] = livenessChecks.asJava

  /**
   * Java API
   */
  def getCheckTimeout(): java.time.Duration = checkTimeout.asJava
}
