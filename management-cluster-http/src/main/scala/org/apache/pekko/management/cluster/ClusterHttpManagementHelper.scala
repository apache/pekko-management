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

package org.apache.pekko.management.cluster

import org.apache.pekko.cluster.Member

object ClusterHttpManagementHelper {
  def memberToClusterMember(m: Member): ClusterMember =
    ClusterMember(s"${m.address}", s"${m.uniqueAddress.longUid}", s"${m.status}", m.roles)

  private[pekko] def oldestPerRole(thisDcMembers: Seq[Member]): Map[String, String] = {
    val roles: Set[String] = thisDcMembers.flatMap(_.roles).toSet
    roles.map(role => (role, oldestForRole(thisDcMembers, role))).toMap
  }

  private def oldestForRole(cluster: Seq[Member], role: String): String = {
    val forRole = cluster.filter(_.roles.contains(role))

    if (forRole.isEmpty)
      "<unknown>"
    else
      forRole.min(Member.ageOrdering).address.toString

  }
}
