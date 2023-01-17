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

package docs;

import akka.actor.ActorSystem;
import akka.discovery.Discovery;
import akka.discovery.ServiceDiscovery;

public class KubernetesApiDiscoveryDocsTest {
  public void loadKubernetesApiDiscovery() {
    ActorSystem system = ActorSystem.create();
    //#kubernetes-api-discovery
    ServiceDiscovery discovery = Discovery.get(system).loadServiceDiscovery("kubernetes-api");
    //#kubernetes-api-discovery
  }
}
