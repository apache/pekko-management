/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.discovery.awsapi.ec2;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

/**
 * Implement this interface to customize the {@link ClientOverrideConfiguration.Builder} used when
 * creating the EC2 async client. The FQCN of the implementing class can be set via the
 * {@code pekko.discovery.aws-api-ec2-tag-based-async.client-config} config entry.
 *
 * <p>The implementing class must have either a no-argument constructor or a constructor that takes
 * an {@link org.apache.pekko.actor.ActorSystem}.
 */
public interface Ec2AsyncClientConfigCustomizer {

  /**
   * Customize the given builder. This method is called after the default retry strategy has been
   * set, so it is possible to override it.
   *
   * @param builder the builder to customize
   * @return the possibly modified builder
   */
  ClientOverrideConfiguration.Builder apply(ClientOverrideConfiguration.Builder builder);
}
