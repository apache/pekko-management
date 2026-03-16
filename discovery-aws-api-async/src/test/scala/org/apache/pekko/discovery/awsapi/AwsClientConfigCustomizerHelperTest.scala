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

package org.apache.pekko.discovery.awsapi

import org.apache.pekko
import pekko.actor.{ ActorSystem, ExtendedActorSystem }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration

class AwsClientConfigCustomizerHelperTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("AwsClientConfigCustomizerHelperTest")
  private val extSystem = system.asInstanceOf[ExtendedActorSystem]

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  test("no-arg customizer is applied to the builder") {
    val fqcn = classOf[NoArgTestCustomizer].getName
    val config = AwsClientConfigCustomizerHelper.buildClientOverrideConfiguration(extSystem, Some(fqcn))
    import scala.jdk.OptionConverters._
    config.apiCallTimeout().toScala.map(_.toSeconds) should ===(Some(42L))
  }

  test("ExtendedActorSystem-arg customizer is applied to the builder") {
    val fqcn = classOf[SystemArgTestCustomizer].getName
    val config = AwsClientConfigCustomizerHelper.buildClientOverrideConfiguration(extSystem, Some(fqcn))
    import scala.jdk.OptionConverters._
    config.apiCallTimeout().toScala.map(_.toSeconds) should ===(Some(99L))
  }

  test("when no FQCN is provided a default config is returned") {
    val config = AwsClientConfigCustomizerHelper.buildClientOverrideConfiguration(extSystem, None)
    config should not be null
  }

}

/** Test customizer with a no-argument constructor. */
class NoArgTestCustomizer extends AwsAsyncClientConfigCustomizer {
  override def apply(builder: ClientOverrideConfiguration.Builder): ClientOverrideConfiguration.Builder =
    builder.apiCallTimeout(java.time.Duration.ofSeconds(42))
}

/** Test customizer with a single-argument constructor taking an [[ExtendedActorSystem]]. */
class SystemArgTestCustomizer(system: ExtendedActorSystem) extends AwsAsyncClientConfigCustomizer {
  override def apply(builder: ClientOverrideConfiguration.Builder): ClientOverrideConfiguration.Builder =
    builder.apiCallTimeout(java.time.Duration.ofSeconds(99))
}
