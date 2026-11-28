<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License. You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Release Notes (2.0.x)

## 2.0.0-M2

This is a milestone release and is aimed at testing this new major version by early adopters. This is experimental. This release should not be used in production.

This release is meant to be used with other Pekko 2.0 module releases.

See the [GitHub Milestone for 2.0.0-M2](https://github.com/apache/pekko-management/milestone/9?closed=1) for a fuller list of changes.

### Additions

* Java API: `PekkoManagementSettings` has new `getHttpRouteProvidersReadOnly` and `getHttpGracefulTerminationTimeout` getters ([PR1065](https://github.com/apache/pekko-management/pull/1065))
* Java API: a custom join decider can be written in Java by implementing `org.apache.pekko.management.cluster.bootstrap.javadsl.JoinDecider` ([PR1065](https://github.com/apache/pekko-management/pull/1065))
* Cluster Bootstrap can send HTTP Basic credentials when probing contact points, configured with `pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth`. The credentials are only sent over HTTPS, unless `basic-auth.allow-insecure-http` is enabled ([#436](https://github.com/apache/pekko-management/issues/436))

### Changes

* Cluster Bootstrap: a probe request to a contact point now times out after the new `pekko.management.cluster.bootstrap.contact-point.probing-request-timeout` (1 second by default) and is retried until `probing-failure-timeout` has passed. Before, a request only timed out after `probing-failure-timeout` and was never retried. A request is never given longer than what is left of `probing-failure-timeout`, so a configuration with a `probing-failure-timeout` shorter than 1 second keeps its bound ([#209](https://github.com/apache/pekko-management/issues/209))

### Removals

* The discovery-marathon-api module has been removed, because Apache Mesos has been retired. There is no replacement. The 1.x releases still include it ([#672](https://github.com/apache/pekko-management/issues/672))

## 2.0.0-M1

This is a milestone release and is aimed at testing this new major version by early adopters. This is experimental. This release should not be used in production.

Java 17 is the minimum supported version for Java. Scala 2.12 support has been removed.

This release is meant to be used with other Pekko 2.0 module releases.

See the [GitHub Milestone for 2.0.0-M1](https://github.com/apache/pekko-management/milestone/6?closed=1) for a fuller list of changes.

### Additions

* New module: rolling-update-kubernetes ([PR732](https://github.com/apache/pekko-management/pull/732))

### Changes

* discovery-consul module switched to Kiwiproject Consul Client ([PR548](https://github.com/apache/pekko-management/pull/548))

### Dependency Upgrades

* Amazon SDK 2.42.35
* Log4j 2.25.4
* Logback 1.5.18
