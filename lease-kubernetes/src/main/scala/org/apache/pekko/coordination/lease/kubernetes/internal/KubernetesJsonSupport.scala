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
    spec: CustomSpec,
    kind: String = "Lease",
    apiVersion: String = "pekko.apache.org/v1")

/**
 * INTERNAL API
 */
@InternalApi
case class Metadata(name: String, resourceVersion: Option[String])

/**
 * INTERNAL API
 */
@InternalApi
case class CustomSpec(owner: String, time: Long)

/**
 * INTERNAL API
 */
@InternalApi
case class NativeLeaseResource(
    metadata: Metadata,
    spec: NativeSpec,
    kind: String = "Lease",
    apiVersion: String = "coordination.k8s.io/v1")

/**
 * INTERNAL API
 */
@InternalApi
case class NativeSpec(holderIdentity: String, acquireTime: String)

/**
 * INTERNAL API
 */
@InternalApi
trait KubernetesJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val metadataFormat: JsonFormat[Metadata] = jsonFormat2(Metadata.apply)
  implicit val customSpecFormat: JsonFormat[CustomSpec] = jsonFormat2(CustomSpec.apply)
  implicit val nativeSpecFormat: JsonFormat[NativeSpec] = jsonFormat2(NativeSpec.apply)
  implicit val leaseCustomResourceFormat: RootJsonFormat[LeaseCustomResource] = jsonFormat4(LeaseCustomResource.apply)
  implicit val leaseNativeResourceFormat: RootJsonFormat[NativeLeaseResource] = jsonFormat4(NativeLeaseResource.apply)
}
