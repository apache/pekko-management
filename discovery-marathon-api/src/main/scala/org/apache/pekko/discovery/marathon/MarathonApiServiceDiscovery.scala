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

import java.net.InetAddress
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.http.scaladsl._
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import AppList._
import JsonFormat._
import pekko.annotation.ApiMayChange
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.event.{ LogSource, Logging }

@ApiMayChange
object MarathonApiServiceDiscovery {

  /**
   * Finds relevant targets given a pod list. Note that this doesn't filter by name as it is the job of the selector
   * to do that.
   */
  private[marathon] def targets(appList: AppList, portName: String): Seq[ResolvedTarget] = {
    def dockerContainerPort(app: App) =
      app.container
        .flatMap(_.docker)
        .flatMap(_.portMappings)
        .getOrElse(Seq.empty)
        .zipWithIndex
        .find(_._1.name.contains(portName))
        .map(_._2)

    def appContainerPort(app: App) =
      app.container
        .flatMap(_.portMappings)
        .getOrElse(Seq.empty)
        .zipWithIndex
        .find(_._1.name.contains(portName))
        .map(_._2)

    def appPort(app: App) =
      app.portDefinitions.getOrElse(Seq.empty).zipWithIndex.find(_._1.name.contains(portName)).map(_._2)

    // Tasks in the API don't have port names, so we have to look to the app to get the position we use
    def portIndex(app: App) =
      dockerContainerPort(app).orElse(appContainerPort(app)).orElse(appPort(app))

    for {
      app <- appList.apps
      task <- app.tasks.getOrElse(Seq.empty)
      portNumber <- portIndex(app)
      taskHost <- task.host
      taskPorts <- task.ports
      taskPekkoManagementPort <- taskPorts.lift(portNumber)
    } yield {
      ResolvedTarget(
        host = taskHost,
        port = Some(taskPekkoManagementPort),
        address = Try(InetAddress.getByName(taskHost)).toOption)
    }
  }
}

/**
 * Service discovery that uses the Marathon API.
 */
@ApiMayChange
class MarathonApiServiceDiscovery(implicit system: ActorSystem) extends ServiceDiscovery {
  import MarathonApiServiceDiscovery._
  import system.dispatcher

  private val log = Logging(system, getClass)(LogSource.fromClass)

  private val http = Http()

  private val settings = Settings(system)

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    val uri =
      Uri(settings.appApiUrl).withQuery(
        Uri.Query(
          "embed" -> "apps.tasks",
          "embed" -> "apps.deployments",
          "label" -> settings.appLabelQuery.format(lookup.serviceName)))

    val request = HttpRequest(uri = uri)

    log.info("Requesting seed nodes by: {}", request.uri)

    val portName = lookup.portName match {
      case Some(name) => name
      case None       => settings.appPortName
    }

    for {
      response <- http.singleRequest(request)

      entity <- response.entity.toStrict(resolveTimeout)

      appList <- {
        log.debug("Marathon API entity: [{}]", entity.data.utf8String)
        val unmarshalled = Unmarshal(entity).to[AppList]

        unmarshalled.failed.foreach { _ =>
          log.error(
            "Failed to unmarshal Marathon API response status [{}], entity: [{}], uri: [{}]",
            response.status.value,
            entity.data.utf8String,
            uri)
        }
        unmarshalled
      }

    } yield Resolved(lookup.serviceName, targets(appList, portName))
  }

}
