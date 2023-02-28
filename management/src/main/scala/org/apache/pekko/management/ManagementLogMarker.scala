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

import org.apache.pekko.annotation.ApiMayChange
import org.apache.pekko.annotation.InternalApi
import org.apache.pekko.event.LogMarker

/**
 * This is public with the purpose to document the used markers and properties of log events.
 * No guarantee that it will remain binary compatible, but the marker names and properties
 * are considered public API and will not be changed without notice.
 */
@ApiMayChange
object ManagementLogMarker {

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] object Properties {
    val HttpAddress = "pekkoHttpAddress"
  }

  /**
   * Marker "pekkoManagementBound" of log event when Pekko Management HTTP endpoint has been bound.
   * @param boundAddress The hostname and port of the bound interface. Included as property "pekkoHttpAddress".
   */
  def boundHttp(boundAddress: String): LogMarker =
    LogMarker("pekkoManagementBound", Map(Properties.HttpAddress -> boundAddress))

  /**
   * Marker "pekkoReadinessCheckFailed" of log event when a readiness check fails.
   */
  val readinessCheckFailed: LogMarker =
    LogMarker("pekkoReadinessCheckFailed")

  /**
   * Marker "pekkoLivenessCheckFailed" of log event when a readiness check fails.
   */
  val livenessCheckFailed: LogMarker =
    LogMarker("pekkoLivenessCheckFailed")

}
