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

import scala.util.{ Failure, Success }

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.retries.DefaultRetryStrategy

private[awsapi] object AwsClientConfigCustomizerHelper {

  /**
   * Builds a [[ClientOverrideConfiguration]] with a `doNotRetry` strategy applied first.
   * If `clientConfigFqcn` is non-empty the named class is instantiated (preferring a
   * single-argument constructor taking an [[ExtendedActorSystem]], falling back to a
   * no-argument constructor) and applied to the builder after the retry strategy, so
   * that the customizer may override it.
   */
  def buildClientOverrideConfiguration(
      system: ExtendedActorSystem,
      clientConfigFqcn: Option[String]): ClientOverrideConfiguration = {
    val builder =
      ClientOverrideConfiguration.builder().retryStrategy(DefaultRetryStrategy.doNotRetry())
    clientConfigFqcn match {
      case Some(fqcn) =>
        val customizer = system.dynamicAccess
          .createInstanceFor[AwsAsyncClientConfigCustomizer](fqcn, List(classOf[ExtendedActorSystem] -> system))
          .recoverWith {
            case _: NoSuchMethodException =>
              system.dynamicAccess.createInstanceFor[AwsAsyncClientConfigCustomizer](fqcn, Nil)
          }
        customizer match {
          case Success(c)  => c.apply(builder).build()
          case Failure(ex) =>
            throw new Exception(
              s"Could not create AwsAsyncClientConfigCustomizer instance of '$fqcn'. " +
              "Make sure the class exists and has either a no-argument constructor or a single-argument constructor that takes an ExtendedActorSystem.",
              ex)
        }
      case None =>
        builder.build()
    }
  }
}
