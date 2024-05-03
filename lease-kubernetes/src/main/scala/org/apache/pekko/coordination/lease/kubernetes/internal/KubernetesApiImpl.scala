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

import scala.concurrent.Future

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.InternalApi
import pekko.coordination.lease.LeaseException
import pekko.coordination.lease.kubernetes.{ KubernetesSettings, LeaseResource }
import pekko.http.scaladsl.marshalling.Marshal
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.unmarshalling.Unmarshal

/**
 * Could be shared between leases: https://github.com/akka/akka-management/issues/680
 * INTERNAL API
 */
@InternalApi private[pekko] class KubernetesApiImpl(system: ActorSystem, settings: KubernetesSettings)
    extends AbstractKubernetesApiImpl(system, settings) {

  import system.dispatcher

  /*
curl -v -X PUT localhost:8080/apis/pekko.apache.org/v1/namespaces/lease/leases/sbr-lease --data-binary "@sbr-lease.yml" -H "Content-Type: application/yaml"
PUTs must contain resourceVersions. Response:
409: Resource version is out of date
200 if it is updated
   */
  /**
   * Update the named resource.
   *
   * Must [[readOrCreateLeaseResource]] to first to get a resource version.
   *
   * Can return one of three things:
   *  - Future.Failure, e.g. timed out waiting for k8s api server to respond
   *  - Future.success[Left(resource)]: the update failed due to version not matching current in the k8s api server.
   *    In this case the current resource is returned so the version can be used for subsequent calls
   *  - Future.success[Right(resource)]: Returns the LeaseResource that contains the clientName and new version.
   *    The new version should be used for any subsequent calls
   */
  override def updateLeaseResource(
      leaseName: String,
      ownerName: String,
      version: String,
      time: Long = System.currentTimeMillis()): Future[Either[LeaseResource, LeaseResource]] = {
    val lcr = LeaseCustomResource(Metadata(leaseName, Some(version)), CustomSpec(ownerName, System.currentTimeMillis()))
    for {
      entity <- Marshal(lcr).to[RequestEntity]
      response <- {
        log.debug("updating {} to {}", leaseName, lcr)
        makeRequest(
          requestForPath(pathForLease(leaseName), method = HttpMethods.PUT, entity),
          s"Timed out updating lease [$leaseName] to owner [$ownerName]. It is not known if the update happened")
      }
      result <- response.status match {
        case StatusCodes.OK =>
          Unmarshal(response.entity)
            .to[LeaseCustomResource]
            .map(updatedLcr => {
              log.debug("LCR after update: {}", updatedLcr)
              Right(toLeaseResource(updatedLcr))
            })
        case StatusCodes.Conflict =>
          getLeaseResource(leaseName).flatMap {
            case None =>
              Future.failed(
                new LeaseException(s"GET after PUT conflict did not return a lease. Lease[$leaseName-$ownerName]"))
            case Some(lr) =>
              log.debug("LeaseResource read after conflict: {}", lr)
              Future.successful(Left(lr))
          }
        case StatusCodes.Unauthorized =>
          handleUnauthorized(response)
        case unexpected =>
          Unmarshal(response.entity)
            .to[String]
            .flatMap(body => {
              Future.failed(
                new LeaseException(
                  s"PUT for lease $leaseName returned unexpected status code $unexpected. Body: $body"))
            })
      }
    } yield result
  }

  override def getLeaseResource(name: String): Future[Option[LeaseResource]] = {
    val fResponse = makeRequest(requestForPath(pathForLease(name)), s"Timed out reading lease $name")
    for {
      response <- fResponse
      entity <- response.entity.toStrict(settings.bodyReadTimeout)
      lr <- response.status match {
        case StatusCodes.OK =>
          // it exists, parse it
          log.debug("Resource {} exists: {}", name, entity)
          Unmarshal(entity)
            .to[LeaseCustomResource]
            .map(lcr => {
              Some(toLeaseResource(lcr))
            })
        case StatusCodes.NotFound =>
          response.discardEntityBytes()
          log.debug("Resource does not exist: {}", name)
          Future.successful(None)
        case StatusCodes.Unauthorized =>
          handleUnauthorized(response)
        case unexpected =>
          Unmarshal(response.entity)
            .to[String]
            .flatMap(body => {
              Future.failed(new LeaseException(
                s"Unexpected response from API server when retrieving lease StatusCode: $unexpected. Body: $body"))
            })
      }
    } yield lr
  }

  override def pathForLease(name: String): Uri.Path =
    Uri.Path.Empty / "apis" / "pekko.apache.org" / "v1" / "namespaces" / namespace / "leases" / name
      .replaceAll("[^\\d\\w\\-\\.]", "")
      .toLowerCase

  override def createLeaseResource(name: String): Future[Option[LeaseResource]] = {
    val lcr = LeaseCustomResource(Metadata(name, None), CustomSpec("", System.currentTimeMillis()))
    for {
      entity <- Marshal(lcr).to[RequestEntity]
      response <- makeRequest(
        requestForPath(pathForLease(name), HttpMethods.POST, entity = entity),
        s"Timed out creating lease $name")
      responseEntity <- response.entity.toStrict(settings.bodyReadTimeout)
      lr <- response.status match {
        case StatusCodes.Created =>
          log.debug("lease resource created")
          Unmarshal(responseEntity).to[LeaseCustomResource].map(lcr => Some(toLeaseResource(lcr)))
        case StatusCodes.Conflict =>
          log.debug("creation of lease resource failed as already exists. Will attempt to read again")
          entity.discardBytes()
          // someone else has created it
          Future.successful(None)
        case StatusCodes.Unauthorized =>
          handleUnauthorized(response)
        case unexpected =>
          responseEntity
            .toStrict(settings.bodyReadTimeout)
            .flatMap(e => Unmarshal(e).to[String])
            .flatMap(body => {
              Future.failed(
                new LeaseException(
                  s"Unexpected response from API server when creating Lease StatusCode: $unexpected. Body: $body"))
            })
      }
    } yield lr
  }

  private def toLeaseResource(lcr: LeaseCustomResource) = {
    log.debug("Converting {}", lcr)
    require(
      lcr.metadata.resourceVersion.isDefined,
      s"LeaseCustomResource returned from Kubernetes without a resourceVersion: $lcr")
    val owner = lcr.spec.owner match {
      case null | "" => None
      case other     => Some(other)
    }
    LeaseResource(owner, lcr.metadata.resourceVersion.get, lcr.spec.time)
  }
}
