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

package org.apache.pekko.management.cluster.bootstrap.internal

import org.apache.pekko.actor.ActorPath
import org.apache.pekko.http.scaladsl.model.Uri.Host
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HttpContactPointBootstrapSpec extends AnyWordSpec with Matchers {
  "HttpContactPointBootstrap" should {
    "use a safe name when connecting over IPv6" in {
      val name = HttpContactPointBootstrap.name(Host("[fe80::1013:2070:258a:c662]"), 443)
      ActorPath.isValidPathElement(name) should be(true)
    }
  }
}
