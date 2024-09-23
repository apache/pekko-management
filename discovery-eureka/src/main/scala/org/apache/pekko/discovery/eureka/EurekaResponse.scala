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

import scala.collection.immutable

object EurekaResponse {
  case class Application(name: String, instance: immutable.Seq[Instance])
  case class Instance(hostName: String, app: String, vipAddress: String, ipAddr: Option[String],
      status: String, port: PortWrapper, securePort: PortWrapper, dataCenterInfo: Option[DataCenterInfo],
      lastDirtyTimestamp: String)
  case class Status()
  case class PortWrapper(port: Int, enabled: Boolean)
  case class DataCenterInfo(name: String = "MyOwn",
      clz: String = "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo")
}

import EurekaResponse._

case class EurekaResponse(application: Application, errorCode: Option[String])
