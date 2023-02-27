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

package akka.management.cluster

import com.typesafe.config.Config

final class ClusterHttpManagementSettings(val config: Config) {
  config.getConfig("akka.management.cluster")

  // placeholder for potential future configuration... currently nothing is configured here
}
