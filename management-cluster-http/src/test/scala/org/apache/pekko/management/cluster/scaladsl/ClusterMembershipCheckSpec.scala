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
import pekko.cluster.MemberStatus
import pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ClusterMembershipCheckSpec
    extends TestKit(ActorSystem("ClusterHealthCheck"))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    shutdown(system)
  }

  "Cluster Health" should {
    "be unhealthy if current state not one of healthy states" in {
      val chc = new ClusterMembershipCheck(
        system,
        () => MemberStatus.joining,
        new ClusterMembershipCheckSettings(Set(MemberStatus.Up)))

      chc().futureValue shouldEqual false
    }
    "be unhealthy if current state is one of healthy states" in {
      val chc =
        new ClusterMembershipCheck(
          system,
          () => MemberStatus.Up,
          new ClusterMembershipCheckSettings(Set(MemberStatus.Up)))

      chc().futureValue shouldEqual true
    }
  }
}
