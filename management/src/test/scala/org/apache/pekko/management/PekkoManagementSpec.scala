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

package org.apache.pekko.management

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PekkoManagementSpec extends TestKit(ActorSystem("PekkoManagementSpec"))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers {

  val config = ConfigFactory.parseString(
    """
      |pekko.remote.log-remote-lifecycle-events = off
      |pekko.remote.netty.tcp.port = 0
      |pekko.remote.artery.canonical.port = 0
      |
      |pekko.management.http.port = 0
      |#pekko.loglevel = DEBUG
    """.stripMargin)

  val mgmt = PekkoManagement(system)

  "Pekko Management" should {
    "successfully start" in {
      // Starting twice with the same config actually starts once:
      val started = mgmt.start(_.withReadOnly(true)).futureValue
      val started2 = mgmt.start(_.withReadOnly(true)).futureValue
      started should be(started2)

      // But starting with a different config fails:
      val e = mgmt.start(_.withReadOnly(false)).failed.futureValue
      e.getMessage should be("Management extension already started with different configuration parameters")
    }
  }

  override def afterAll(): Unit = {
    mgmt.stop()
  }
}
