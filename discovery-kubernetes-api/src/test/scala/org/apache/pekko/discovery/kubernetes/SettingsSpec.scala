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

import scala.concurrent.duration._

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
    "default api-poll-mode to list" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.apiPollMode shouldBe "list"
      } finally {
        system.terminate()
      }
    }
    "support api-poll-mode override to watch" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          api-poll-mode = "watch"
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val settings = Settings(system)
        settings.apiPollMode shouldBe "watch"
      } finally {
        system.terminate()
      }
    }
    "default watch-reconnect-delay to 1s" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.watchReconnectDelay shouldBe 1.second
      } finally {
        system.terminate()
      }
    }
    "support watch-reconnect-delay override" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-reconnect-delay = 3s
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val settings = Settings(system)
        settings.watchReconnectDelay shouldBe 3.seconds
      } finally {
        system.terminate()
      }
    }
    "default watch-on-error-reconnect-delay to 5s" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.watchOnErrorReconnectDelay shouldBe 5.seconds
      } finally {
        system.terminate()
      }
    }
    "default watch-max-frame-length to 1 MiB" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.watchMaxFrameLength shouldBe 1024 * 1024
      } finally {
        system.terminate()
      }
    }
    "reject an unknown api-poll-mode rather than silently polling" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          api-poll-mode = "Watch"
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val ex = intercept[IllegalArgumentException](Settings(system).apiPollMode)
        ex.getMessage should include("api-poll-mode must be")
      } finally {
        system.terminate()
      }
    }
    "reject a non-positive watch-max-frame-length" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-max-frame-length = 0
        }
      """)
      val system = ActorSystem("test", config)
      try {
        intercept[IllegalArgumentException](Settings(system).watchMaxFrameLength)
      } finally {
        system.terminate()
      }
    }
  }
}
