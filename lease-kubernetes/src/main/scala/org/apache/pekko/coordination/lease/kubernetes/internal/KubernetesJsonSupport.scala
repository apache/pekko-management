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

package org.apache.pekko.coordination.lease.kubernetes.internal

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{ DefaultJsonProtocol, JsonFormat, RootJsonFormat }

/**
 * INTERNAL API
 */
@InternalApi
case class LeaseCustomResource(
    metadata: Metadata,
    spec: Spec,
    kind: String = "Lease",
    apiVersion: String = "akka.io/v1")

/**
 * INTERNAL API
 */
@InternalApi
case class Metadata(name: String, resourceVersion: Option[String])

/**
 * INTERNAL API
 */
@InternalApi
case class Spec(owner: String, time: Long)

/**
 * INTERNAL API
 */
@InternalApi
trait KubernetesJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val metadataFormat: JsonFormat[Metadata] = jsonFormat2(Metadata)
  implicit val specFormat: JsonFormat[Spec] = jsonFormat2(Spec)
  implicit val leaseCustomResourceFormat: RootJsonFormat[LeaseCustomResource] = jsonFormat4(LeaseCustomResource)
}
