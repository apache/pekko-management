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

package org.apache.pekko.discovery.marathon

import scala.collection.immutable.Seq

object AppList {
  case class App(container: Option[Container], portDefinitions: Option[Seq[PortDefinition]], tasks: Option[Seq[Task]])
  case class Container(portMappings: Option[Seq[PortMapping]], docker: Option[Docker])
  case class Docker(portMappings: Option[Seq[PortMapping]])
  case class Task(host: Option[String], ports: Option[Seq[Int]])
  case class PortDefinition(name: Option[String], port: Option[Int])
  case class PortMapping(servicePort: Option[Int], name: Option[String])
}

import AppList._

case class AppList(apps: Seq[App])
