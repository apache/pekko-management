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

package org.apache.pekko.discovery.kubernetes

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.discovery.kubernetes.PodList.{
  Added,
  Container,
  ContainerPort,
  ContainerStatus,
  Deleted,
  Error,
  ListMeta,
  Metadata,
  Modified,
  Pod,
  PodSpec,
  PodStatus,
  WatchEvent,
  WatchEventType
}
import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object JsonFormat extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val containerPortFormat: JsonFormat[ContainerPort] = jsonFormat2(ContainerPort.apply)
  implicit val containerFormat: JsonFormat[Container] = jsonFormat2(Container.apply)
  implicit val podSpecFormat: JsonFormat[PodSpec] = jsonFormat1(PodSpec.apply)
  implicit val containerStatusFormat: JsonFormat[ContainerStatus] = jsonFormat2(ContainerStatus.apply)
  implicit val podStatusFormat: JsonFormat[PodStatus] = jsonFormat3(PodStatus.apply)
  implicit val metadataFormat: JsonFormat[Metadata] = jsonFormat4(Metadata.apply)
  implicit val podFormat: JsonFormat[Pod] = jsonFormat3(Pod.apply)
  implicit val listMetaFormat: JsonFormat[ListMeta] = jsonFormat1(ListMeta.apply)
  implicit val podListFormat: RootJsonFormat[PodList] = jsonFormat2(PodList.apply)

  implicit val watchEventTypeFormat: JsonFormat[WatchEventType] = new JsonFormat[WatchEventType] {
    def write(obj: WatchEventType): JsValue = obj match {
      case Added    => JsString("ADDED")
      case Modified => JsString("MODIFIED")
      case Deleted  => JsString("DELETED")
      case Error    => JsString("ERROR")
    }

    def read(json: JsValue): WatchEventType = json match {
      case JsString("ADDED")    => Added
      case JsString("MODIFIED") => Modified
      case JsString("DELETED")  => Deleted
      case JsString("ERROR")    => Error
      case other                => throw new DeserializationException(s"Unknown watch event type: $other")
    }
  }

  implicit val watchEventFormat: RootJsonFormat[WatchEvent] = new RootJsonFormat[WatchEvent] {
    def write(obj: WatchEvent): JsValue =
      JsObject("type" -> obj.eventType.toJson, "object" -> obj.pod.toJson)

    def read(json: JsValue): WatchEvent = json.asJsObject("WatchEvent expected") match {
      case JsObject(fields) =>
        val eventType = fields("type").convertTo[WatchEventType]
        val pod = fields("object").convertTo[Pod]
        WatchEvent(eventType, pod)
      case other => throw new DeserializationException(s"Expected JsObject, got $other")
    }
  }
}
