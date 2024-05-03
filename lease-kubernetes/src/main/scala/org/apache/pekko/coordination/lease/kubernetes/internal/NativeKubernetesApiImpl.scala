/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.coordination.lease.kubernetes.internal

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.annotation.InternalApi
import org.apache.pekko.coordination.lease.kubernetes.internal.NativeKubernetesApiImpl.RFC3339MICRO_FORMATTER
import org.apache.pekko.coordination.lease.kubernetes.{ KubernetesSettings, LeaseResource }
import org.apache.pekko.coordination.lease.LeaseException
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import java.time.{ Instant, LocalDateTime, ZoneId }
import java.time.format.{ DateTimeFormatter, DateTimeFormatterBuilder }
import java.time.temporal.ChronoField
import scala.concurrent.Future

object NativeKubernetesApiImpl {
  // From https://github.com/kubernetes-client/java/blob/e50fb2a6f30d4f07e3922430307e5e09058aaea1/kubernetes/src/main/java/io/kubernetes/client/openapi/JSON.java#L57
  val RFC3339MICRO_FORMATTER: DateTimeFormatter =
    new DateTimeFormatterBuilder().parseDefaulting(ChronoField.OFFSET_SECONDS,
      0).append(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")).optionalStart.appendFraction(
      ChronoField.NANO_OF_SECOND, 6, 6, true).optionalEnd.appendLiteral("Z").toFormatter
}

/**
 * Could be shared between leases: https://github.com/akka/akka-management/issues/680
 * INTERNAL API
 */
@InternalApi private[pekko] class NativeKubernetesApiImpl(system: ActorSystem, settings: KubernetesSettings)
    extends AbstractKubernetesApiImpl(system, settings) {

  import system.dispatcher

  /**
   * Update the named resource.
   *
   * Must [[readOrCreateLeaseResource]] to first to get a resource version.
   *
   * Can return one of three things:
   *  - Future.Failure, e.g. timed out waiting for k8s api server to respond
   *  - Future.sucess[Left(resource)]: the update failed due to version not matching current in the k8s api server.
   *    In this case the current resource is returned so the version can be used for subsequent calls
   *  - Future.sucess[Right(resource)]: Returns the LeaseResource that contains the clientName and new version.
   *    The new version should be used for any subsequent calls
   */
  override def updateLeaseResource(
      leaseName: String,
      ownerName: String,
      version: String,
      time: Long = System.currentTimeMillis()): Future[Either[LeaseResource, LeaseResource]] = {
    val lcr = NativeLeaseResource(Metadata(leaseName, Some(version)), NativeSpec(ownerName, currentTimeRFC3339))
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
            .to[NativeLeaseResource]
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
            .to[NativeLeaseResource]
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
    Uri.Path.Empty / "apis" / "coordination.k8s.io" / "v1" / "namespaces" / namespace / "leases" / name
      .replaceAll("[^\\d\\w\\-\\.]", "")
      .toLowerCase

  override def createLeaseResource(name: String): Future[Option[LeaseResource]] = {
    val lcr = NativeLeaseResource(Metadata(name, None), NativeSpec("", currentTimeRFC3339))
    for {
      entity <- Marshal(lcr).to[RequestEntity]
      response <- makeRequest(
        requestForPath(pathForLease(""), HttpMethods.POST, entity = entity),
        s"Timed out creating lease $name")
      responseEntity <- response.entity.toStrict(settings.bodyReadTimeout)
      lr <- response.status match {
        case StatusCodes.Created =>
          log.debug("lease resource created")
          Unmarshal(responseEntity).to[NativeLeaseResource].map(lcr => Some(toLeaseResource(lcr)))
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

  private def currentTimeRFC3339: String = {
    RFC3339MICRO_FORMATTER.withZone(ZoneId.of("UTC")).format(Instant.now())
  }

  private def toLeaseResource(lcr: NativeLeaseResource) = {
    log.debug("Converting {}", lcr)
    require(
      lcr.metadata.resourceVersion.isDefined,
      s"LeaseCustomResource returned from Kubernetes without a resourceVersion: $lcr")
    val owner = lcr.spec.holderIdentity match {
      case null | "" => None
      case other     => Some(other)
    }
    LeaseResource(owner, lcr.metadata.resourceVersion.get,
      LocalDateTime.parse(lcr.spec.acquireTime, RFC3339MICRO_FORMATTER)
        .atZone(ZoneId.of("UTC"))
        .toInstant
        .toEpochMilli)
  }

}
