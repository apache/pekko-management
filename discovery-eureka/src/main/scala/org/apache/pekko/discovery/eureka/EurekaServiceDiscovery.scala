/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pekko.discovery.eureka

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.eureka.EurekaServiceDiscovery.{ pick, targets }
import pekko.discovery.eureka.JsonFormat._
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.event.{ LogSource, Logging }
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{ HttpRequest, MediaRange, MediaTypes, Uri }
import pekko.http.scaladsl.model.headers._
import pekko.http.scaladsl.unmarshalling.Unmarshal

import java.net.InetAddress
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object EurekaServiceDiscovery {
  private[eureka] def pick(
      instances: immutable.Seq[EurekaResponse.Instance]): Future[immutable.Seq[EurekaResponse.Instance]] = {
    Future.successful(instances.collect {
      case instance if instance.status == "UP" => instance
    })
  }

  private[eureka] def targets(instances: immutable.Seq[EurekaResponse.Instance]): immutable.Seq[ResolvedTarget] = {
    instances.map { instance =>
      ResolvedTarget(
        host = instance.hostName,
        port = Some(instance.port.port),
        address = instance.ipAddr.flatMap(ip => Try(InetAddress.getByName(ip)).toOption))
    }
  }
}

class EurekaServiceDiscovery(implicit system: ActorSystem) extends ServiceDiscovery {

  import system.dispatcher

  private val log = Logging(system, getClass)(LogSource.fromClass)
  private val settings = EurekaSettings(system)
  private val (scheme, host, port, path) =
    (settings.scheme, settings.host, settings.port, settings.path)
  private val http = Http()

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[ServiceDiscovery.Resolved] = {

    val uriPath = Uri.Path.Empty / path / "apps" / lookup.serviceName
    val uri = Uri.from(scheme = scheme, host = host, port = port).withPath(uriPath)
    val request = HttpRequest(uri = uri,
      headers = immutable.Seq(`Accept-Encoding`(HttpEncodings.gzip), Accept(MediaRange(MediaTypes.`application/json`))))

    log.info("Requesting seed nodes by: {}", request.uri)

    for {
      response <- http.singleRequest(request)
      entity <- response.entity.toStrict(resolveTimeout)
      response <- {
        log.debug("Eureka response: [{}]", entity.data.utf8String)
        val unmarshalled = Unmarshal(entity).to[EurekaResponse]
        unmarshalled.failed.foreach { _ =>
          log.error(
            "Failed to unmarshal Eureka response status [{}], entity: [{}], uri: [{}]",
            response.status.value,
            entity.data.utf8String,
            uri)
        }
        unmarshalled
      }
      instances <- pick(response.application.instance)
    } yield Resolved(lookup.serviceName, targets(instances))

  }

}
