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

package akka.coordination.lease.kubernetes
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

// For testing locally with a kubectl proxy 8080
// the actual spec is run in kubernetes from Jenkins
abstract class LocalLeaseSpec extends LeaseSpec {
  private lazy val _system = ActorSystem(
    "LocalLeaseSpec",
    ConfigFactory.parseString("""
     akka.loglevel = INFO
    akka.coordination.lease.kubernetes {
      api-service-host = localhost
      api-service-port = 8080
      namespace = "akka-lease-tests"
      namespace-path = ""
      secure-api-server = false
    }
    """))

  override def system = _system
}
