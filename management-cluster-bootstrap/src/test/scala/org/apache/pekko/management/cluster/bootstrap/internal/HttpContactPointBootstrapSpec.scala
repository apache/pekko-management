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

package org.apache.pekko.management.cluster.bootstrap.internal

import java.nio.file.NoSuchFileException

import org.apache.pekko
import pekko.actor.{ ActorPath, ActorSystem }
import pekko.event.Logging
import pekko.management.cluster.bootstrap.ClusterBootstrapSettings
import pekko.http.scaladsl.model.Uri.Host
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HttpContactPointBootstrapSpec extends AnyWordSpec with Matchers {

  "HttpContactPointBootstrap" should {
    "use a safe name when connecting over IPv6" in {
      val name = HttpContactPointBootstrap.name(Host("[fe80::1013:2070:258a:c662]"), 443)
      ActorPath.isValidPathElement(name) should be(true)
    }
    "generate SSLContext with default config" in {
      val sys = ActorSystem("HttpContactPointBootstrapSpec")
      val log = Logging(sys, classOf[HttpContactPointBootstrapSpec])
      try {
        val settings = new ClusterBootstrapSettings(sys.settings.config, log)
        HttpContactPointBootstrap.generateSSLContext(settings) should not be null
      } finally {
        sys.terminate()
      }
    }
    "generate SSLContext with cert" in {
      val sys = ActorSystem("HttpContactPointBootstrapSpec")
      val log = Logging(sys, classOf[HttpContactPointBootstrapSpec])
      try {
        val cfg = ConfigFactory.parseString("""
          pekko.management.cluster.bootstrap.contact-point.http-client {
            ca-path = "management-cluster-bootstrap/src/test/files/ca.crt"
          }""").withFallback(sys.settings.config)
        val settings = new ClusterBootstrapSettings(cfg, log)
        HttpContactPointBootstrap.generateSSLContext(settings) should not be null
      } finally {
        sys.terminate()
      }
    }
    "fail to generate SSLContext with missing cert" in {
      val sys = ActorSystem("HttpContactPointBootstrapSpec")
      val log = Logging(sys, classOf[HttpContactPointBootstrapSpec])
      try {
        val cfg = ConfigFactory.parseString("""
          pekko.management.cluster.bootstrap.contact-point.http-client {
            ca-path = "management-cluster-bootstrap/src/test/files/non-existent.crt"
          }""").withFallback(sys.settings.config)
        val settings = new ClusterBootstrapSettings(cfg, log)
        intercept[NoSuchFileException] {
          HttpContactPointBootstrap.generateSSLContext(settings)
        }
      } finally {
        sys.terminate()
      }
    }
    "fail to generate SSLContext with bad tls-version" in {
      val sys = ActorSystem("HttpContactPointBootstrapSpec")
      val log = Logging(sys, classOf[HttpContactPointBootstrapSpec])
      try {
        val cfg = ConfigFactory.parseString("""
          pekko.management.cluster.bootstrap.contact-point.http-client {
            ca-path = "management-cluster-bootstrap/src/test/files/ca.crt"
            tls-version = "BAD_VERSION"
          }""").withFallback(sys.settings.config)
        val settings = new ClusterBootstrapSettings(cfg, log)
        val nsae = intercept[java.security.NoSuchAlgorithmException] {
          HttpContactPointBootstrap.generateSSLContext(settings)
        }
        nsae.getMessage.contains("BAD_VERSION") should be(true)
      } finally {
        sys.terminate()
      }
    }

  }
}
