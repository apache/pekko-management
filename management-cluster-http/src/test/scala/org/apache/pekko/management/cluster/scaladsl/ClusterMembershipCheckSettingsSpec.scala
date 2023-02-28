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

import org.apache.pekko.cluster.MemberStatus
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClusterMembershipCheckSettingsSpec extends AnyWordSpec with Matchers {

  "Member status parsing" must {
    "be case insensitive" in {
      ClusterMembershipCheckSettings.memberStatus("WeaklyUp") shouldEqual MemberStatus.WeaklyUp
      ClusterMembershipCheckSettings.memberStatus("Weaklyup") shouldEqual MemberStatus.WeaklyUp
      ClusterMembershipCheckSettings.memberStatus("weaklyUp") shouldEqual MemberStatus.WeaklyUp
      ClusterMembershipCheckSettings.memberStatus("Up") shouldEqual MemberStatus.Up
      ClusterMembershipCheckSettings.memberStatus("Exiting") shouldEqual MemberStatus.Exiting
      ClusterMembershipCheckSettings.memberStatus("down") shouldEqual MemberStatus.Down
      ClusterMembershipCheckSettings.memberStatus("joininG") shouldEqual MemberStatus.Joining
      ClusterMembershipCheckSettings.memberStatus("leaving") shouldEqual MemberStatus.Leaving
      ClusterMembershipCheckSettings.memberStatus("removed") shouldEqual MemberStatus.Removed
    }

    "have a useful error message for invalid values" in {

      intercept[IllegalArgumentException] {
        ClusterMembershipCheckSettings.memberStatus("cats") shouldEqual MemberStatus.Removed
      }.getMessage shouldEqual "'cats' is not a valid MemberStatus. See reference.conf for valid values"
    }
  }

}
