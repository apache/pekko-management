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

package org.apache.pekko.discovery.awsapi.ec2

import java.net.InetAddress
import java.util.concurrent.TimeoutException

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.util.Try

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.annotation.ApiMayChange
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.awsapi.AwsClientConfigCustomizerHelper
import pekko.discovery.awsapi.ec2.AsyncEc2TagBasedServiceDiscovery.parseFiltersString
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.pattern.after
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.ec2.Ec2AsyncClient
import software.amazon.awssdk.services.ec2.model.{ DescribeInstancesRequest, Filter }

@ApiMayChange
object AsyncEc2TagBasedServiceDiscovery {

  private[ec2] def parseFiltersString(filtersString: String): List[Filter] =
    filtersString
      .split(";")
      .filter(_.nonEmpty)
      .map(kv => kv.split("="))
      .toList
      .map(kv => {
        require(kv.length == 2, s"failed to parse filter '${kv.mkString("=")}': expected key=value format")
        Filter.builder().name(kv(0)).values(kv(1)).build()
      })

}

@ApiMayChange
class AsyncEc2TagBasedServiceDiscovery(system: ExtendedActorSystem) extends ServiceDiscovery {

  private val config = system.settings.config.getConfig("pekko.discovery.aws-api-ec2-tag-based-async")

  private val tagKey = config.getString("tag-key")

  private val clientConfigFqcn: Option[String] =
    config.getString("client-config") match {
      case ""   => None
      case fqcn => Some(fqcn)
    }

  private val otherFiltersString = config.getString("filters")
  private val otherFilters = parseFiltersString(otherFiltersString)

  private val preDefinedPorts =
    config.getIntList("ports").asScala.toList match {
      case Nil  => None
      case list => Some(list)
    }

  private val runningInstancesFilter =
    Filter.builder().name("instance-state-name").values("running").build()

  private lazy val ec2Client: Ec2AsyncClient = {
    val overrideConfig = AwsClientConfigCustomizerHelper.buildClientOverrideConfiguration(system, clientConfigFqcn)
    val httpClient = NettyNioAsyncHttpClient.create()
    val client = Ec2AsyncClient.builder().overrideConfiguration(overrideConfig).httpClient(httpClient).build()
    system.registerOnTermination(client.close())
    client
  }

  private implicit val ec: ExecutionContext = system.dispatcher

  private def getInstances(
      filters: List[Filter],
      nextToken: Option[String],
      accumulator: List[String] = Nil): Future[List[String]] = {

    val requestBuilder = DescribeInstancesRequest.builder().filters(filters.asJava)
    nextToken.foreach(requestBuilder.nextToken)
    val request = requestBuilder.build()

    ec2Client.describeInstances(request).asScala.flatMap { response =>
      val ips: List[String] =
        response.reservations().asScala.toList
          .flatMap(_.instances().asScala.toList)
          .map(_.privateIpAddress())

      val accumulatedIps = accumulator ++ ips

      Option(response.nextToken()) match {
        case None =>
          Future.successful(accumulatedIps)
        case nextPageToken @ Some(_) =>
          getInstances(filters, nextPageToken, accumulatedIps)
      }
    }
  }

  override def lookup(query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] =
    Future.firstCompletedOf(
      Seq(
        after(resolveTimeout, using = system.scheduler)(
          Future.failed(new TimeoutException(s"Lookup for [$query] timed-out, within [$resolveTimeout]!"))),
        lookup(query)))

  def lookup(query: Lookup): Future[Resolved] = {
    val tagFilter = Filter.builder().name("tag:" + tagKey).values(query.serviceName).build()
    val allFilters: List[Filter] = runningInstancesFilter :: tagFilter :: otherFilters

    getInstances(allFilters, None).map { ips =>
      val resolvedTargets = ips.flatMap { ip =>
        preDefinedPorts match {
          case None =>
            ResolvedTarget(host = ip, port = None, address = Try(InetAddress.getByName(ip)).toOption) :: Nil
          case Some(ports) =>
            ports.map(p =>
              ResolvedTarget(host = ip, port = Some(p), address = Try(InetAddress.getByName(ip)).toOption))
        }
      }
      Resolved(query.serviceName, resolvedTargets)
    }
  }

}
