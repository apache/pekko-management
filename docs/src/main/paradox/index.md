<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  license agreements; and to You under the Apache License, version 2.0:

    https://www.apache.org/licenses/LICENSE-2.0

  This file is part of the Apache Pekko project, which was derived from Akka.
-->

# Apache Pekko Management

Pekko Management is a suite of tools for operating Pekko Clusters.

## Overview

Pekko Management consists of multiple modules:

 * @ref[pekko-management](pekko-management.md) is the base module that provides an extensible HTTP management endpoint for Pekko management tools as well a @ref[pluggable health check mechanism](healthchecks.md).
 * @ref[pekko-cluster-bootstrap](bootstrap/index.md) helps bootstrapping a Pekko cluster using @extref:[Pekko Discovery](pekko:discovery/index.html).
 * @ref[pekko-management-cluster-http](cluster-http-management.md) is a module that provides HTTP endpoints for introspecting and managing Pekko clusters.
 * @ref[kubernetes-lease](kubernetes-lease.md) a lease backed by Kubernetes API server. 
 * @ref[rolling-updates](rolling-updates.md) provides recommendations and extensions for having smoother rolling updates.

 As well as @extref:[Pekko Discovery](pekko:discovery/index.html) methods for:
 
 * @ref[Kubernetes API](discovery/kubernetes.md)
 * @ref[Consul](discovery/consul.md)
 * @ref[AWS](discovery/aws.md)

Various parts of Pekko management can be used together for deploying Pekko Cluster applications to Kubernetes.

 * @ref[Deploying Pekko Cluster to Kubernetes](kubernetes-deployment/index.md)

@@@ index

  - [Release Notes](release-notes/index.md)
  - [License Report](license-report.md)
  - [Deploy guide for Kubernetes](kubernetes-deployment/index.md)
  - [Pekko Management](pekko-management.md)
  - [Migrating from older versions](migration.md)
  - [Health Checks](healthchecks.md)
  - [Pekko Cluster Bootstrap](bootstrap/index.md)
  - [Pekko Discovery Methods](discovery/index.md)
  - [Pekko Cluster Management (HTTP)](cluster-http-management.md)
  - [Pekko Cluster Management (JMX)](cluster-jmx-management.md)
  - [Dynamic Log Levels](loglevels/index.md)
  - [Pekko Coordination Lease for Kubernetes](kubernetes-lease.md)
  - [Rolling Updates](rolling-updates.md)
@@@
