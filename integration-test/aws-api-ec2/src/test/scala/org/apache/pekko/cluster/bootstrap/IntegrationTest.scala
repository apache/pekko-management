/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package org.apache.pekko.cluster.bootstrap

import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{ DescribeInstancesRequest, Filter, Reservation }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model._
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.event.Logging
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.HttpRequest
import pekko.management.cluster.{ ClusterHttpManagementJsonProtocol, ClusterMembers }
import pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.PatienceConfiguration.{ Interval, Timeout }
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span, SpanSugar }
import spray.json._

import scala.concurrent.{ Await, Future }
import scala.jdk.CollectionConverters._
import scala.language.postfixOps

trait HttpClient {

  implicit val system: ActorSystem = ActorSystem("simple")

  import system.dispatcher

  import scala.concurrent.duration._

  val http = Http()

  def httpGetRequest(url: String): Future[(Int, String)] = {
    http.singleRequest(HttpRequest(uri = url))
      .flatMap(r => r.entity.toStrict(3.seconds).map(s => r.status -> s))
      .flatMap(t =>
        t._2.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String).map(_.filter(_ >= ' '))
          .map(r => t._1.intValue() -> r))
  }

}

class IntegrationTest extends AnyFunSuite with Eventually with BeforeAndAfterAll with ScalaFutures with HttpClient
    with ClusterHttpManagementJsonProtocol with SpanSugar with Matchers {

  private val buildId: String = System.getenv("BUILD_ID")
  assert(buildId != null, "BUILD_ID environment variable has to be defined")

  private val log = Logging(system, classOf[IntegrationTest])

  private val instanceCount = 3

  private val bucket = System.getenv("BUCKET") // bucket where zip file resulting from sbt universal:packageBin is stored

  private val region = "us-east-1"

  private val stackName = s"PekkoManagementIntegrationTestEC2TagBased-${buildId.replace(".", "-")}"

  private val awsCfClient = CloudFormationClient.builder().region(Region.of(region)).build()

  private val awsEc2Client = AmazonEC2ClientBuilder.standard().withRegion(region).build()

  // Patience settings for the part where we wait for the CloudFormation script to complete
  private val createStackPatience: PatienceConfig =
    PatienceConfig(
      timeout = 15.minutes,
      interval = 10.seconds)

  // Patience settings for the actual cluster bootstrap part.
  // Once the CloudFormation stack has CREATE_COMPLETE status, the EC2 instances are
  // still "initializing" (seems to take a very long time) so we add some additional patience for that.
  private val clusterBootstrapPatience: PatienceConfig =
    PatienceConfig(
      timeout = 12.minutes,
      interval = 5.seconds)

  private var clusterPublicIps: List[String] = List()

  private var clusterPrivateIps: List[String] = List()

  override def beforeAll(): Unit = {

    log.info("setting up infrastructure using CloudFormation")

    val template = readTemplateFromResourceFolder("CloudFormation/pekko-cluster.json")

    val myIp: String = s"$getMyIp/32"

    val createStackRequest = CreateStackRequest.builder()
      .capabilities(Capability.CAPABILITY_IAM)
      .stackName(stackName)
      .templateBody(template)
      .parameters(
        Parameter.builder().parameterKey("Build").parameterValue(
          s"https://s3.amazonaws.com/$bucket/$buildId/app.zip").build(),
        Parameter.builder().parameterKey("SSHLocation").parameterValue(myIp).build(),
        Parameter.builder().parameterKey("InstanceCount").parameterValue(instanceCount.toString).build(),
        Parameter.builder().parameterKey("InstanceType").parameterValue("m3.xlarge").build(),
        Parameter.builder().parameterKey("KeyPair").parameterValue("none").build(),
        Parameter.builder().parameterKey("Purpose").parameterValue(s"demo-$buildId").build())
      .build()

    awsCfClient.createStack(createStackRequest)

    val describeStacksRequest = DescribeStacksRequest.builder().stackName(stackName).build()

    var dsr: DescribeStacksResponse = null

    def conditions: Boolean = (dsr.stacks().size() == 1) && {
      val stack = dsr.stacks().get(0)
      stack.stackStatus() == StackStatus.CREATE_COMPLETE &&
      stack.outputs().size() >= 1 &&
      stack.outputs().asScala.exists(_.outputKey() == "AutoScalingGroupName")
    }

    implicit val patienceConfig: PatienceConfig = createStackPatience

    eventually {

      log.info("CloudFormation stack name is {}, waiting for a CREATE_COMPLETE", stackName)

      dsr = awsCfClient.describeStacks(describeStacksRequest)

      conditions shouldBe true

    }

    if (conditions) {

      log.info("got CREATE_COMPLETE, trying to obtain IPs of EC2 instances launched")

      val asgName =
        dsr.stacks().get(0).outputs().asScala.find(_.outputKey() == "AutoScalingGroupName").get.outputValue()

      val ips: List[(String, String)] = awsEc2Client
        .describeInstances(new DescribeInstancesRequest()
          .withFilters(new Filter("tag:aws:autoscaling:groupName", List(asgName).asJava)))
        .getReservations
        .asScala
        .flatMap((r: Reservation) =>
          r.getInstances.asScala.map(instance => (instance.getPublicIpAddress, instance.getPrivateIpAddress)))
        .toList
        .filter(ips =>
          ips._1 != null && ips._2 != null) // TODO: investigate whether there are edge cases that may makes this necessary

      clusterPublicIps = ips.map(_._1)
      clusterPrivateIps = ips.map(_._2)

      log.info("EC2 instances launched have the following public IPs {}", clusterPublicIps.mkString(", "))

    }

  }

  private def readTemplateFromResourceFolder(path: String): String = scala.io.Source.fromResource(path).mkString

  // we need this in order to tell AWS to allow the machine running the integration test to connect to the EC2 instances'
  // port 7626
  private def getMyIp: String = {
    val myIp: Future[(Int, String)] = httpGetRequest("http://checkip.amazonaws.com")
    val result = Await.result(myIp, atMost = 3.seconds)
    assert(result._1 == 200, "http://checkip.amazonaws.com did not return 200 OK")
    result._2
  }

  test("Integration Test for EC2 Tag Based Discovery") {

    implicit val patienceConfig: PatienceConfig = clusterBootstrapPatience
    val httpCallTimeout = Timeout(Span(3, Seconds))

    clusterPublicIps should have size instanceCount
    clusterPrivateIps should have size instanceCount

    val expectedNodes: Set[String] = clusterPrivateIps.map(ip => s"pekko.tcp://demo@$ip:7355").toSet

    eventually {

      log.info(
        "querying the Cluster Http Management interface of each node, eventually we should see a well formed cluster")

      clusterPublicIps.foreach { (nodeIp: String) =>
        {

          val result = httpGetRequest(s"http://$nodeIp:7626/cluster/members").futureValue(httpCallTimeout)
          result._1 should ===(200)
          result._2 should not be empty

          val clusterMembers = result._2.parseJson.convertTo[ClusterMembers]

          clusterMembers.members should have size instanceCount
          clusterMembers.members.count(_.status == "Up") should ===(instanceCount)
          clusterMembers.members.map(_.node) should ===(expectedNodes)

          clusterMembers.unreachable should be(empty)
          clusterMembers.leader shouldBe defined
          clusterMembers.oldest shouldBe defined

        }
      }
    }
  }

  // this will remove every resource created by the integration test from the AWS account
  // this includes security rules, IAM roles, auto-scaling groups, EC2 instances etc.
  override def afterAll(): Unit = {
    log.info("tearing down infrastructure")
    eventually(timeout = Timeout(3.minutes), interval = Interval(3.seconds)) {
      // we put this into an an eventually block since we want to retry
      // for a while, in case it throws an exception.
      awsCfClient.deleteStack(DeleteStackRequest.builder().stackName(stackName).build())
    }
    system.terminate()
  }

}
