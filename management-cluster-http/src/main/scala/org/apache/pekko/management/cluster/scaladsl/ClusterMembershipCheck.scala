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

package org.apache.pekko.management.cluster.scaladsl

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.InternalApi
import pekko.cluster.{ Cluster, MemberStatus }
import pekko.util.Helpers

import com.typesafe.config.Config

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/**
 * Internal API
 */
@InternalApi
private[pekko] object ClusterMembershipCheckSettings {
  def memberStatus(status: String): MemberStatus =
    Helpers.toRootLowerCase(status) match {
      case "weaklyup" => MemberStatus.WeaklyUp
      case "up"       => MemberStatus.Up
      case "exiting"  => MemberStatus.Exiting
      case "down"     => MemberStatus.Down
      case "joining"  => MemberStatus.Joining
      case "leaving"  => MemberStatus.Leaving
      case "removed"  => MemberStatus.Removed
      case invalid =>
        throw new IllegalArgumentException(
          s"'$invalid' is not a valid MemberStatus. See reference.conf for valid values")
    }
  def apply(config: Config): ClusterMembershipCheckSettings =
    new ClusterMembershipCheckSettings(config.getStringList("ready-states").asScala.map(memberStatus).toSet)
}

final class ClusterMembershipCheckSettings(val readyStates: Set[MemberStatus])

final class ClusterMembershipCheck @InternalApi private[pekko] (
    system: ActorSystem,
    selfStatus: () => MemberStatus,
    settings: ClusterMembershipCheckSettings)
    extends (() => Future[Boolean]) {

  def this(system: ActorSystem) =
    this(
      system,
      () => Cluster(system).selfMember.status,
      ClusterMembershipCheckSettings(system.settings.config.getConfig("pekko.management.cluster.health-check")))

  override def apply(): Future[Boolean] = {
    Future.successful(settings.readyStates.contains(selfStatus()))
  }
}
