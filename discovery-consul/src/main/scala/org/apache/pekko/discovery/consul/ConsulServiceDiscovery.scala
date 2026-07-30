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

package org.apache.pekko.discovery.consul

import com.google.common.net.HostAndPort
import org.apache.pekko
import pekko.actor.{ ActorSystem, CoordinatedShutdown }
import pekko.annotation.ApiMayChange
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.consul.ConsulServiceDiscovery._
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.dispatch.Dispatchers.DefaultBlockingDispatcherId
import pekko.pattern.after
import org.kiwiproject.consul.Consul
import org.kiwiproject.consul.async.ConsulResponseCallback
import org.kiwiproject.consul.model.ConsulResponse
import org.kiwiproject.consul.model.catalog.CatalogService
import org.kiwiproject.consul.option.Options

import java.net.InetAddress
import java.util
import java.util.concurrent.TimeoutException
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.CollectionConverters._
import scala.util.Try

@ApiMayChange
class ConsulServiceDiscovery(system: ActorSystem) extends ServiceDiscovery {

  private val settings = ConsulSettings.get(system)
  private val consul =
    Consul
      .builder()
      .withHostAndPort(HostAndPort.fromParts(settings.consulHost, settings.consulPort))
      .withConnectTimeoutMillis(settings.connectTimeout.toMillis)
      .withReadTimeoutMillis(settings.readTimeout.toMillis)
      .withWriteTimeoutMillis(settings.writeTimeout.toMillis)
      .build()
  private val blockingEc: ExecutionContext = system.dispatchers.lookup(DefaultBlockingDispatcherId)

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "consul-close") { () =>
    Future {
      consul.destroy()
      pekko.Done
    }(system.dispatcher)
  }

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    implicit val ec: ExecutionContext = system.dispatcher
    Future.firstCompletedOf(
      Seq(
        after(resolveTimeout, using = system.scheduler)(
          Future.failed(new TimeoutException(s"Lookup for [$lookup] timed-out, within [$resolveTimeout]!"))),
        lookupInConsul(lookup.serviceName)))
  }

  private def lookupInConsul(name: String)(implicit executionContext: ExecutionContext): Future[Resolved] = {
    val consulResult = for {
      servicesWithTags <- getServicesWithTags
      nameTag = settings.applicationNameTagPrefix + name
      serviceIds = servicesWithTags.getResponse
        .entrySet()
        .asScala
        .filter(e => e.getValue.contains(nameTag))
        .map(_.getKey)
      catalogServices <- boundedTraverse(serviceIds.toSeq)(id => getService(id).map(_.getResponse.asScala.toList))
      resolvedTargets <- Future.traverse(catalogServices.flatten.toSeq) { catalogService =>
        Future(extractResolvedTargetFromCatalogService(catalogService))(blockingEc)
      }
    } yield resolvedTargets
    consulResult.map(targets => Resolved(name, scala.collection.immutable.Seq(targets: _*)))
  }

  private def extractResolvedTargetFromCatalogService(catalogService: CatalogService) = {
    val port = catalogService.getServiceTags.asScala
      .find(_.startsWith(settings.applicationPekkoManagementPortTagPrefix))
      .map(_.replace(settings.applicationPekkoManagementPortTagPrefix, ""))
      .flatMap { maybePort =>
        Try(maybePort.toInt).toOption
      }
    val address = catalogService.getServiceAddress
    ResolvedTarget(
      host = address,
      port = Some(port.getOrElse(catalogService.getServicePort)),
      address = Try(InetAddress.getByName(address)).toOption)
  }

  private def boundedTraverse[A, B](items: Seq[A])(f: A => Future[B])(
      implicit ec: ExecutionContext): Future[Seq[B]] = {
    def loop(remaining: Seq[A], acc: Seq[B]): Future[Seq[B]] = {
      if (remaining.isEmpty) Future.successful(acc.reverse)
      else {
        val (batch, rest) = remaining.splitAt(settings.parallelism)
        Future.traverse(batch)(f).flatMap(results => loop(rest, results.reverse ++ acc))
      }
    }
    loop(items, Seq.empty)
  }

  private def getServicesWithTags: Future[ConsulResponse[util.Map[String, util.List[String]]]] = {
    ((callback: ConsulResponseCallback[util.Map[String, util.List[String]]]) =>
          consul.catalogClient().getServices(callback)).asFuture
  }

  private def getService(name: String) =
    ((callback: ConsulResponseCallback[util.List[CatalogService]]) =>
          consul.catalogClient().getService(name, Options.BLANK_QUERY_OPTIONS, callback)).asFuture

}

@ApiMayChange
object ConsulServiceDiscovery {

  implicit class ConsulResponseFutureDecorator[T](f: ConsulResponseCallback[T] => Unit) {
    def asFuture: Future[ConsulResponse[T]] = {
      val callback = new ConsulResponseFutureCallback[T]
      Try(f(callback)).recover[Unit] {
        case ex: Throwable => callback.fail(ex)
      }
      callback.future
    }
  }

  final case class ConsulResponseFutureCallback[T]() extends ConsulResponseCallback[T] {

    private val promise = Promise[ConsulResponse[T]]()

    def fail(exception: Throwable) = promise.failure(exception)

    def future: Future[ConsulResponse[T]] = promise.future

    override def onComplete(consulResponse: ConsulResponse[T]): Unit = {
      promise.success(consulResponse)
    }

    override def onFailure(throwable: Throwable): Unit = {
      promise.failure(throwable)
    }
  }

}
