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

package org.apache.pekko.discovery.awsapi.ecs

import java.net.InetAddress
import java.util.concurrent.TimeoutException
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.ApiMayChange
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.awsapi.ecs.AsyncEcsServiceDiscovery.{ resolveTasks, Tag }
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.pattern.after
import pekko.util.FutureConverters._
import pekko.util.ccompat.JavaConverters._
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.retries.DefaultRetryStrategy
import software.amazon.awssdk.services.ecs._
import software.amazon.awssdk.services.ecs.model.{ Tag => _, _ }

@ApiMayChange
class AsyncEcsServiceDiscovery(system: ActorSystem) extends ServiceDiscovery {

  private[this] val config = system.settings.config.getConfig("pekko.discovery.aws-api-ecs-async")
  private[this] val cluster = config.getString("cluster")
  private[this] val tags = config
    .getConfigList("tags")
    .asScala
    .map { tagConfig =>
      Tag(
        tagConfig.getString("key"),
        tagConfig.getString("value"))
    }
    .toList

  private[this] lazy val ecsClient = {
    val conf = ClientOverrideConfiguration.builder().retryStrategy(DefaultRetryStrategy.doNotRetry()).build()
    val httpClient = NettyNioAsyncHttpClient.create()
    EcsAsyncClient.builder().overrideConfiguration(conf).httpClient(httpClient).build()
  }

  private[this] implicit val ec: ExecutionContext = system.dispatcher

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] =
    Future.firstCompletedOf(
      Seq(
        after(resolveTimeout, using = system.scheduler)(
          Future.failed(new TimeoutException("Future timed out!"))),
        resolveTasks(ecsClient, cluster, lookup.serviceName, tags).map(tasks =>
          Resolved(
            serviceName = lookup.serviceName,
            addresses = for {
              task <- tasks
              container <- task.containers().asScala
              networkInterface <- container.networkInterfaces().asScala
            } yield {
              val address = networkInterface.privateIpv4Address()
              ResolvedTarget(host = address, port = None, address = Try(InetAddress.getByName(address)).toOption)
            }))))

}

@ApiMayChange
object AsyncEcsServiceDiscovery {

  case class Tag(key: String, value: String)

  private def resolveTasks(ecsClient: EcsAsyncClient, cluster: String, serviceName: String, tags: List[Tag])(
      implicit ec: ExecutionContext): Future[Seq[Task]] =
    for {
      taskArns <- listTaskArns(ecsClient, cluster, serviceName)
      tasks <- describeTasks(ecsClient, cluster, taskArns)
      tasksWithTags = tasks.filter { task =>
        val ecsTags = task.tags().asScala.map(tag => Tag(tag.key(), tag.value())).toList
        tags.diff(ecsTags).isEmpty
      }
    } yield tasksWithTags

  private[this] def listTaskArns(
      ecsClient: EcsAsyncClient,
      cluster: String,
      serviceName: String,
      pageTaken: Option[String] = None,
      accumulator: Seq[String] = Seq.empty)(implicit ec: ExecutionContext): Future[Seq[String]] =
    for {
      listTasksResponse <- ecsClient.listTasks(
        ListTasksRequest
          .builder()
          .cluster(cluster)
          .serviceName(serviceName)
          .nextToken(pageTaken.orNull)
          .desiredStatus(DesiredStatus.RUNNING)
          .build()).asScala
      accumulatedTasksArns = accumulator ++ listTasksResponse.taskArns().asScala
      taskArns <- listTasksResponse.nextToken() match {
        case null =>
          Future.successful(accumulatedTasksArns)

        case nextPageToken =>
          listTaskArns(
            ecsClient,
            cluster,
            serviceName,
            Some(nextPageToken),
            accumulatedTasksArns)
      }
    } yield taskArns

  private[this] def describeTasks(ecsClient: EcsAsyncClient, cluster: String, taskArns: Seq[String])(
      implicit ec: ExecutionContext): Future[Seq[Task]] =
    for {
      // Each DescribeTasksRequest can contain at most 100 task ARNs.
      describeTasksResponses <- Future.traverse(taskArns.grouped(100))(taskArnGroup =>
        ecsClient.describeTasks(
          DescribeTasksRequest.builder().cluster(cluster).tasks(taskArnGroup.asJava).include(
            TaskField.TAGS).build()).asScala)
      tasks = describeTasksResponses.flatMap(_.tasks().asScala).toList
    } yield tasks

}
