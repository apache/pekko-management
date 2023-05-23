/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.management.cluster.bootstrap

import org.apache.pekko
import pekko.actor.Address
import pekko.annotation.ApiMayChange
import pekko.annotation.InternalApi
import pekko.event.LogMarker

/**
 * This is public with the purpose to document the used markers and properties of log events.
 * No guarantee that it will remain binary compatible, but the marker names and properties
 * are considered public API and will not be changed without notice.
 */
@ApiMayChange
object BootstrapLogMarker {

  /**
   * INTERNAL API
   */
  @InternalApi private[pekko] object Properties {
    val ContactPoints = "pekkoContactPoints"
    val SeedNodes = "pekkoSeedNodes"
  }

  /**
   * Marker "pekkoBootstrapInit" of log event when Pekko Bootstrap is initialized.
   */
  val init: LogMarker =
    LogMarker("pekkoBootstrapInit")

  /**
   * Marker "pekkoBootstrapResolved" of log event when contact points have been resolved.
   * @param contactPoints The hostname and port of the resolved and selected contact points. Included as property "pekkoContactPoints".
   */
  def resolved(contactPoints: Iterable[String]): LogMarker =
    LogMarker("pekkoBootstrapResolved", Map(Properties.ContactPoints -> contactPoints.mkString(", ")))

  /**
   * Marker "pekkoBootstrapResolveFailed" of log event when resolve of contact points failed.
   */
  val resolveFailed: LogMarker =
    LogMarker("pekkoBootstrapResolveFailed")

  /**
   * Marker "pekkoBootstrapInProgress" of log event when bootstrap is in progress.
   * @param contactPoints The hostname and port of the resolved and selected contact points. Included as property "pekkoContactPoints".
   * @param seedNodes The address of the observed seed nodes of the Pekko Cluster. Included as property "pekkoSeedNodes".
   */
  def inProgress(contactPoints: Set[String], seedNodes: Set[Address]): LogMarker =
    LogMarker(
      "pekkoBootstrapInProgress",
      Map(Properties.ContactPoints -> contactPoints.mkString(", "), Properties.SeedNodes -> seedNodes.mkString(", ")))

  /**
   * Marker "pekkoBootstrapSeedNodes" of log event when seed nodes of the Pekko Cluster have been discovered.
   * @param seedNodes The address of the observed seed nodes of the Pekko Cluster. Included as property "pekkoSeedNodes".
   */
  def seedNodes(seedNodes: Set[Address]): LogMarker =
    LogMarker("pekkoBootstrapSeedNodes", Map(Properties.SeedNodes -> seedNodes.mkString(", ")))

  /**
   * Marker "pekkoBootstrapJoin" of log event when joining the seed nodes of an existing Pekko Cluster.
   * @param seedNodes The address of the seed nodes of the Pekko Cluster. Included as property "pekkoSeedNodes".
   */
  def join(seedNodes: Set[Address]): LogMarker =
    LogMarker("pekkoBootstrapJoin", Map(Properties.SeedNodes -> seedNodes.mkString(", ")))

  /**
   * Marker "pekkoBootstrapJoinSelf" of log event when joining self to form a new Pekko Cluster.
   */
  val joinSelf: LogMarker =
    LogMarker("pekkoBootstrapJoinSelf")

  /**
   * Marker "pekkoBootstrapResolveFailed" of log event when resolve of contact points failed.
   * @param contactPoints The hostname and port of the resolved and selected contact points. Included as property "pekkoContactPoints".
   */
  def seedNodesProbingFailed(contactPoints: Iterable[String]): LogMarker =
    LogMarker("pekkoBootstrapSeedNodesProbingFailed", Map(Properties.ContactPoints -> contactPoints.mkString(", ")))

}
