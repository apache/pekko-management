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

package org.apache.pekko.discovery.kubernetes

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SettingsSpec extends AnyWordSpec with Matchers {

  "Settings" should {
    "default tls-version to v1.2" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.tlsVersion shouldBe "TLSv1.2"
      } finally {
        system.terminate()
      }
    }
    "support tls-version override" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          tls-version = "TLSv1.3"
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val settings = Settings(system)
        settings.tlsVersion shouldBe "TLSv1.3"
      } finally {
        system.terminate()
      }
    }
  }
}