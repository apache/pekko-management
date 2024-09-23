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

package org.apache.pekko.discovery.eureka

import org.apache.pekko.discovery.eureka.EurekaResponse.{ Application, DataCenterInfo, Instance, PortWrapper }
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

object JsonFormat extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val portFormat: JsonFormat[PortWrapper] = new JsonFormat[PortWrapper] {

    override def read(json: JsValue): PortWrapper = {
      json.asJsObject.getFields("$", "@enabled") match {
        case Seq(JsNumber(port), JsString(enabled)) => PortWrapper(port.toInt, enabled.toBoolean)
        case _                                      => throw DeserializationException("PortWrapper expected")
      }
    }

    override def write(obj: PortWrapper): JsValue = JsObject(
      "$" -> JsNumber(obj.port),
      "@enabled" -> JsString(obj.enabled.toString))
  }
  implicit val dataCenterInfoFormat: JsonFormat[DataCenterInfo] = new JsonFormat[DataCenterInfo] {

    override def read(json: JsValue): DataCenterInfo = {
      json.asJsObject.getFields("name", "@class") match {
        case Seq(JsString(name), JsString(clz)) => DataCenterInfo(name, clz)
        case _                                  => throw DeserializationException("DataCenterInfo expected")
      }
    }

    override def write(obj: DataCenterInfo): JsValue = JsObject(
      "name" -> JsString(obj.name),
      "@class" -> JsString(obj.clz))
  }
  implicit val instanceFormat: JsonFormat[Instance] = jsonFormat13(Instance.apply)
  implicit val applicationFormat: JsonFormat[Application] = jsonFormat2(Application.apply)
  implicit val rootFormat: RootJsonFormat[EurekaResponse] = jsonFormat2(EurekaResponse.apply)
}
