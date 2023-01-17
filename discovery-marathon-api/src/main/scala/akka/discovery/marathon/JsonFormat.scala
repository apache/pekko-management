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

package akka.discovery.marathon

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import AppList._

object JsonFormat extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val taskFormat: JsonFormat[Task] = jsonFormat2(Task)
  implicit val portDefinitionFormat: JsonFormat[PortDefinition] = jsonFormat2(PortDefinition)
  implicit val portMappingFormat: JsonFormat[PortMapping] = jsonFormat2(PortMapping)
  implicit val dockerFormat: JsonFormat[Docker] = jsonFormat1(Docker)
  implicit val containerFormat: JsonFormat[Container] = jsonFormat2(Container)
  implicit val appFormat: JsonFormat[App] = jsonFormat3(App)
  implicit val appListFormat: RootJsonFormat[AppList] = jsonFormat1(AppList.apply)
}
