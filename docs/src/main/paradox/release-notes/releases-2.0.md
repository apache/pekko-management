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

@@@ warning

Pekko Management 2.x and Pekko 2.x are not binary compatible with their 1.x releases. Libraries built against Pekko 1.x or Pekko Management 1.x cannot be trusted to work with Pekko Management 2.x, and need to be rebuilt and released for Pekko 2.x. See the @extref:[Pekko 2.0 release notes](pekko:release-notes/releases-2.0.html) for the binary compatibility changes in the core Pekko modules.

Read the @ref:[migration guide](../migration-guide-1.x-2.x.md) before upgrading from Pekko Management 1.x.

@@@

## 2.0.0

Release notes for Apache Pekko Management 2.0.0. This is the first release of a new major version and includes the changes from the 2.0.0-M1 milestone release. See the GitHub milestones for [2.0.0-M1](https://github.com/apache/pekko-management/milestone/6?closed=1) and [2.0.0-M2](https://github.com/apache/pekko-management/milestone/9?closed=1) for a fuller list of changes.

Pekko Management 2.0.0 is not binary compatible with Pekko Management 1.x. Read the @ref:[migration guide](../migration-guide-1.x-2.x.md) before upgrading. It lists the removed module and the behavior and configuration changes.

### Main changes

* Pekko 2.0.0 is the new minimum Pekko version and Pekko HTTP 2.0.x is the new minimum Pekko HTTP version
* Java 17 is the new minimum Java version
* Scala 2.12 support has been dropped
* New module: rolling-update-kubernetes, see @ref:[Rolling Updates](../rolling-updates.md) ([PR732](https://github.com/apache/pekko-management/pull/732))
* The discovery-marathon-api module has been removed, because Apache Mesos has been retired. There is no replacement. The 1.x releases still include it ([#672](https://github.com/apache/pekko-management/issues/672))
* The discovery-consul module switched to the Kiwiproject Consul Client ([PR548](https://github.com/apache/pekko-management/pull/548))

### Additions

* rolling-update-kubernetes: the `PodDeletionCost` extension sets the `controller.kubernetes.io/pod-deletion-cost` annotation so that Kubernetes removes the oldest cluster members last, either directly on the pod or through a `PodCost` custom resource ([PR732](https://github.com/apache/pekko-management/pull/732), [PR774](https://github.com/apache/pekko-management/pull/774))
* rolling-update-kubernetes: the `AppVersionRevision` extension uses the Kubernetes deployment revision as the Pekko Cluster app version ([PR784](https://github.com/apache/pekko-management/pull/784))
* Pekko Management: the HTTP server is terminated gracefully when it is stopped, waiting up to the new `pekko.management.http.graceful-termination-timeout` (5 seconds by default) for requests that are in progress ([PR909](https://github.com/apache/pekko-management/pull/909))
* Kubernetes Lease: failed heartbeats and failed releases are retried, configured with the new `heartbeat-max-retries` and `release-max-retries` settings ([PR911](https://github.com/apache/pekko-management/pull/911))
* Kubernetes Lease: a lease name that has to be truncated gets a hash suffix, so that long names that share a prefix no longer end up as the same lease. The new `lease-name-max-length` and `on-truncate-add-hash-length` settings control this ([PR708](https://github.com/apache/pekko-management/pull/708))
* Consul discovery: new settings for the client timeouts (`connect-timeout`, `read-timeout` and `write-timeout`), for an ACL token (`consul-token`), for TLS (`tls-enabled` and `ca-path`) and for the number of concurrent service lookups (`lookup-parallelism`) ([PR906](https://github.com/apache/pekko-management/pull/906), [PR956](https://github.com/apache/pekko-management/pull/956), [PR965](https://github.com/apache/pekko-management/pull/965))
* Kubernetes API discovery: the `KUBERNETES_NAMESPACE` environment variable, when set, is used as the `pod-namespace` ([PR801](https://github.com/apache/pekko-management/pull/801))
* Java API: `PekkoManagementSettings` has new `getHttpRouteProvidersReadOnly` and `getHttpGracefulTerminationTimeout` getters ([PR1065](https://github.com/apache/pekko-management/pull/1065))
* Java API: a custom join decider can be written in Java by implementing `org.apache.pekko.management.cluster.bootstrap.javadsl.JoinDecider` ([PR1065](https://github.com/apache/pekko-management/pull/1065))
* Java API: `StartupCheckSetup.create`, `ReadinessCheckSetup.create`, `LivenessCheckSetup.create` and `ManagementRouteProviderSettings.withAuth` accept wildcard generic types, so functions that take a supertype or return a subtype can be passed ([PR1033](https://github.com/apache/pekko-management/pull/1033))
* Cluster Bootstrap can send HTTP Basic credentials when probing contact points, configured with `pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth`. The credentials are only sent over HTTPS, unless `basic-auth.allow-insecure-http` is enabled ([#436](https://github.com/apache/pekko-management/issues/436))

### Changes

* Pekko Management logs a warning when the HTTP endpoint is started without an authenticator ([PR934](https://github.com/apache/pekko-management/pull/934))
* Kubernetes API discovery: a request to the Kubernetes API now fails when it has not completed within the resolve timeout ([PR915](https://github.com/apache/pekko-management/pull/915))
* Cluster Bootstrap: `SelfAwareJoinDecider` no longer waits indefinitely for the self contact point to be set ([PR908](https://github.com/apache/pekko-management/pull/908))
* AWS discovery: the AWS clients are closed during coordinated shutdown ([PR907](https://github.com/apache/pekko-management/pull/907), [PR955](https://github.com/apache/pekko-management/pull/955))
* Scala 3 artifacts are built with Scala 3.3 LTS. The build is also tested with Scala 3.9, but those artifacts are not published ([PR1056](https://github.com/apache/pekko-management/pull/1056))

### Bug Fixes

* Java API: `CheckResult.success()` was present even when the health check had failed. It is now empty for a failed check ([PR1032](https://github.com/apache/pekko-management/pull/1032))
* When a request to the Kubernetes API, to Consul or to a bootstrap contact point times out, a response that arrives later is discarded, so that its connection is released ([PR905](https://github.com/apache/pekko-management/pull/905))

### Dependency Changes

* Pekko 2.0.0
* Pekko HTTP 2.0.1
* Scala 2.13.18 and 3.3.9
* Amazon SDK 2.59.9
* Kiwiproject Consul Client 1.12.2
* Jackson 2.23.0
* Log4j 2.26.1
* Logback 1.6.8
