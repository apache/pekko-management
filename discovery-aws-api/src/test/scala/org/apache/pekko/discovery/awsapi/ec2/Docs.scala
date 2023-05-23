/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.discovery.awsapi.ec2

object Docs {

  // #custom-client-config
  // package com.example
  import com.amazonaws.ClientConfiguration
  import com.amazonaws.retry.PredefinedRetryPolicies

  class MyConfiguration extends ClientConfiguration {

    setProxyHost("...") // and/or other things you would like to set

    setRetryPolicy(PredefinedRetryPolicies.NO_RETRY_POLICY)
    // If you're using this module for bootstrapping your Apache Pekko cluster,
    // Cluster Bootstrap already has its own retry/back-off mechanism. To avoid RequestLimitExceeded errors from AWS,
    // disable retries in the EC2 client configuration.
  }
  // #custom-client-config

}
