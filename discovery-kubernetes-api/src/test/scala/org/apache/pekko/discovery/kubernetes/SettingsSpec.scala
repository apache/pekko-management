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

import scala.annotation.nowarn
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SettingsSpec extends AnyWordSpec with Matchers {

  @nowarn("cat=deprecation")
  private def matchesTlsVersion(settings: Settings, version: String): Boolean =
    version match {
      case settings.tlsVersion => true
      case _                   => false
    }

  "Settings" should {
    "default min-tls-version to v1.2" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.minTlsVersion shouldBe "TLSv1.2"
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
        settings.minTlsVersion shouldBe "TLSv1.3"
        // the deprecated accessor still reports the configured value
        (settings.tlsVersion: @nowarn("cat=deprecation")) shouldBe "TLSv1.3"
        // the deprecated accessor is a val, so that it can still be used as a stable identifier
        matchesTlsVersion(settings, "TLSv1.3") shouldBe true
        matchesTlsVersion(settings, "TLSv1.2") shouldBe false
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
    "default watch-idle-timeout to 5 minutes" in {
      val system = ActorSystem("test")
      try {
        Settings(system).watchIdleTimeout shouldBe 5.minutes
      } finally {
        system.terminate()
      }
    }
    "support turning watch-idle-timeout off" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-idle-timeout = off
        }
      """)
      val system = ActorSystem("test", config)
      try {
        Settings(system).watchIdleTimeout shouldBe Duration.Inf
      } finally {
        system.terminate()
      }
    }
    "reject a non-positive watch-idle-timeout" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-idle-timeout = 0s
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val ex = intercept[IllegalArgumentException](Settings(system).watchIdleTimeout)
        ex.getMessage should include("watch-idle-timeout must be")
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
    "default watch-list-timeout to 10s and watch-max-list-length to 8 MiB" in {
      val system = ActorSystem("test")
      try {
        val settings = Settings(system)
        settings.watchListTimeout shouldBe 10.seconds
        settings.watchMaxListLength shouldBe 8L * 1024 * 1024
      } finally {
        system.terminate()
      }
    }
    "support watch-list-timeout and watch-max-list-length overrides" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-list-timeout = 3s
          watch-max-list-length = 64 MiB
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val settings = Settings(system)
        settings.watchListTimeout shouldBe 3.seconds
        settings.watchMaxListLength shouldBe 64L * 1024 * 1024
      } finally {
        system.terminate()
      }
    }
    "reject a non-positive watch-list-timeout" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-list-timeout = 0s
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val ex = intercept[IllegalArgumentException](Settings(system).watchListTimeout)
        ex.getMessage should include("watch-list-timeout must be")
      } finally {
        system.terminate()
      }
    }
    "reject a non-positive watch-max-list-length" in {
      val config = ConfigFactory.parseString("""
        pekko.discovery.kubernetes-api {
          watch-max-list-length = 0
        }
      """)
      val system = ActorSystem("test", config)
      try {
        val ex = intercept[IllegalArgumentException](Settings(system).watchMaxListLength)
        ex.getMessage should include("watch-max-list-length must be")
      } finally {
        system.terminate()
      }
    }
  }
}
