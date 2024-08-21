/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pekko.discovery.eureka

import org.apache.pekko
import pekko.actor.{
  ActorSystem,
  ClassicActorSystemProvider,
  ExtendedActorSystem,
  Extension,
  ExtensionId,
  ExtensionIdProvider
}
import pekko.annotation.ApiMayChange

@ApiMayChange
final class EurekaSettings(system: ExtendedActorSystem) extends Extension {
  private val eurekaConfig = system.settings.config.getConfig("pekko.discovery.eureka")

  val schema: String =  eurekaConfig.getString("eureka-schema")
  val host: String = eurekaConfig.getString("eureka-host")
  val port: Int = eurekaConfig.getInt("eureka-port")
  val path: String = eurekaConfig.getString("eureka-path")
  val groupName: String = eurekaConfig.getString("group-name")
  val statusPageUrl: String = eurekaConfig.getString("status-page-url")
  val healthCheckUrl: String = eurekaConfig.getString("health-page-url")
  val homePageUrl: String = eurekaConfig.getString("home-page-url")
  val servicePort: Int = eurekaConfig.getInt("service-port")
  val serviceName: String = system.name
  val renewInterval: Long = eurekaConfig.getLong("renew-interval")
}

@ApiMayChange
object EurekaSettings extends ExtensionId[EurekaSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): EurekaSettings = super.get(system)

  override def get(system: ClassicActorSystemProvider): EurekaSettings = super.get(system)

  override def lookup: EurekaSettings.type = EurekaSettings

  override def createExtension(system: ExtendedActorSystem): EurekaSettings = new EurekaSettings(system)
}
