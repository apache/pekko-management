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

package org.apache.pekko.discovery.awsapi.ecs

import com.amazonaws.services.ecs.AbstractAmazonECS
import com.amazonaws.services.ecs.model.{
  DescribeTasksRequest,
  DescribeTasksResult,
  ListTasksRequest,
  ListTasksResult,
  Task
}
import org.apache.pekko.discovery.awsapi.ecs.EcsServiceDiscovery.resolveTasks
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

object EcsResolveTasksSpec {

  // Returns `taskArns` over pages of `pageSize`, and echoes back a Task for every ARN it is asked about.
  class StubAmazonECS(taskArns: Seq[String], pageSize: Int) extends AbstractAmazonECS {

    val describeTasksBatchSizes = ListBuffer.empty[Int]

    private val pages = {
      val grouped = taskArns.grouped(pageSize).toVector
      if (grouped.isEmpty) Vector(Seq.empty[String]) else grouped
    }

    override def listTasks(request: ListTasksRequest): ListTasksResult = {
      val pageIndex = Option(request.getNextToken).map(_.toInt).getOrElse(0)
      val result = new ListTasksResult().withTaskArns(pages(pageIndex).asJava)
      if (pageIndex + 1 < pages.size) result.withNextToken((pageIndex + 1).toString) else result
    }

    override def describeTasks(request: DescribeTasksRequest): DescribeTasksResult = {
      val arns = request.getTasks.asScala.toList
      describeTasksBatchSizes += arns.size
      new DescribeTasksResult().withTasks(arns.map(arn => new Task().withTaskArn(arn)).asJava)
    }

  }

}

class EcsResolveTasksSpec extends AnyWordSpec with Matchers {

  import EcsResolveTasksSpec._

  private val arns = (1 to 250).map(i => s"arn:task/$i").toList

  "EcsServiceDiscovery.resolveTasks" should {

    "accumulate every task ARN across paginated listTasks responses" in {
      val ecsClient = new StubAmazonECS(arns, pageSize = 60)

      val tasks = resolveTasks(ecsClient, "default", "my-service")

      tasks.map(_.getTaskArn) should ===(arns)
    }

    "split describeTasks into batches of at most 100 ARNs" in {
      val ecsClient = new StubAmazonECS(arns, pageSize = 250)

      resolveTasks(ecsClient, "default", "my-service") should have size 250
      ecsClient.describeTasksBatchSizes.toList should ===(List(100, 100, 50))
    }

    "not call describeTasks when there are no task ARNs" in {
      val ecsClient = new StubAmazonECS(Seq.empty, pageSize = 100)

      resolveTasks(ecsClient, "default", "my-service") should be(empty)
      ecsClient.describeTasksBatchSizes.toList should be(empty)
    }

  }

}
