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

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import AppList._

object JsonFormat extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val taskFormat: JsonFormat[Task] = jsonFormat2(Task.apply)
  implicit val portDefinitionFormat: JsonFormat[PortDefinition] = jsonFormat2(PortDefinition.apply)
  implicit val portMappingFormat: JsonFormat[PortMapping] = jsonFormat2(PortMapping.apply)
  implicit val dockerFormat: JsonFormat[Docker] = jsonFormat1(Docker.apply)
  implicit val containerFormat: JsonFormat[Container] = jsonFormat2(Container.apply)
  implicit val appFormat: JsonFormat[App] = jsonFormat3(App.apply)
  implicit val appListFormat: RootJsonFormat[AppList] = jsonFormat1(AppList.apply)
}
