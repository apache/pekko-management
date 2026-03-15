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

package org.apache.pekko.discovery.awsapi;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

/**
 * Implement this interface to customize the `ClientOverrideConfiguration.Builder` used when
 * creating an AWS async client. The FQCN of the implementing class can be set via the {@code
 * client-config} config entry of the relevant discovery config section.
 *
 * <p>The implementing class must have either a no-argument constructor or a constructor that takes
 * an {@link org.apache.pekko.actor.ExtendedActorSystem}.
 */
@FunctionalInterface
public interface AwsAsyncClientConfigCustomizer {

  /**
   * Customize the given builder. This method is called after the default retry strategy has been
   * set, so it is possible to override it.
   *
   * @param builder the builder to customize
   * @return the possibly modified builder
   */
  ClientOverrideConfiguration.Builder apply(ClientOverrideConfiguration.Builder builder);
}
