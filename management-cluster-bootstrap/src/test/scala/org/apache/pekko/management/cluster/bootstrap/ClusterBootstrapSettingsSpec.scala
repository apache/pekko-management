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

package org.apache.pekko.management.cluster.bootstrap

import org.apache.pekko.event.NoLogging
import com.typesafe.config.ConfigFactory

/** Currently just tests recent changes. Please enhance accordingly. */
class ClusterBootstrapSettingsSpec extends AbstractBootstrapSpec {

  val config = ConfigFactory.load()

  "ClusterBootstrapSettings" should {

    "have the expected defaults " in {
      val settings = ClusterBootstrapSettings(config, NoLogging)
      settings.newClusterEnabled should ===(true)
    }

    "have the expected overrides " in {
      val overrides = ConfigFactory.parseString("pekko.management.cluster.bootstrap.new-cluster-enabled=off")
      val settings = ClusterBootstrapSettings(overrides.withFallback(config), NoLogging)
      settings.newClusterEnabled should ===(false)
    }

    "fall back to old `form-new-cluster` if present for backward compatibility`" in {
      val settings =
        ClusterBootstrapSettings(
          config.withFallback(ConfigFactory.parseString("""
          pekko.management.cluster.bootstrap {
            form-new-cluster=on
            new-cluster-enabled=off
          }""")),
          NoLogging)
      settings.newClusterEnabled should ===(true)

    }
  }
}
