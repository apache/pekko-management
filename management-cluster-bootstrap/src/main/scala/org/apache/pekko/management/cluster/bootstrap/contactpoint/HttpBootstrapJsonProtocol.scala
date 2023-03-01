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

package org.apache.pekko.management.cluster.bootstrap.contactpoint

import org.apache.pekko
import pekko.actor.{ Address, AddressFromURIString }
import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{ DefaultJsonProtocol, JsString, JsValue, RootJsonFormat }

trait HttpBootstrapJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  import HttpBootstrapJsonProtocol._

  implicit object AddressFormat extends RootJsonFormat[Address] {
    override def read(json: JsValue): Address = json match {
      case JsString(s) => AddressFromURIString.parse(s)
      case invalid     => throw new IllegalArgumentException(s"Illegal address value! Was [$invalid]")
    }

    override def write(obj: Address): JsValue = JsString(obj.toString)
  }
  implicit val SeedNodeFormat: RootJsonFormat[SeedNode] = jsonFormat1(SeedNode)
  implicit val ClusterMemberFormat: RootJsonFormat[ClusterMember] = jsonFormat4(ClusterMember)
  implicit val ClusterMembersFormat: RootJsonFormat[SeedNodes] = jsonFormat2(SeedNodes)
}

object HttpBootstrapJsonProtocol extends DefaultJsonProtocol {

  final case class SeedNode(address: Address)

  // we use Address since we want to know which protocol is being used (tcp, artery, artery-tcp etc)
  final case class ClusterMember(node: Address, nodeUid: Long, status: String, roles: Set[String])
  implicit val clusterMemberOrdering: Ordering[ClusterMember] = Ordering.by(_.node)

  final case class SeedNodes(selfNode: Address, seedNodes: Set[ClusterMember])

}
