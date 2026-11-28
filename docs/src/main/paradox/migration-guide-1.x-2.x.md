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

# Migration from Apache Pekko Management 1.x to 2.x

Apache Pekko Management 2.x requires Apache Pekko 2.x and Apache Pekko HTTP 2.x. See the @extref:[Pekko 1.x to 2.x migration guide](pekko:migration/migration-guide-1.x-2.x.html) for the changes in the core Pekko libraries, and the @extref:[Pekko HTTP 1.x to 2.x migration guide](pekko-http:migration-guide/migration-guide-1.x-2.x.html) for the changes in Pekko HTTP.

The @ref:[2.0 release notes](release-notes/releases-2.0.md) list all the changes, including the additions that need no action when upgrading.

## General changes

* Apache Pekko 2.x is the new minimum Pekko version and Apache Pekko HTTP 2.x is the new minimum Pekko HTTP version.
* Java 17 is the new minimum Java version.
* Scala 2.12 is no longer supported.
* Pekko Management 2.x is not binary compatible with Pekko Management 1.x. All the Pekko Management modules that you use have to be upgraded together, and libraries built against Pekko Management 1.x have to be rebuilt.
* Upgrade to the latest Pekko Management 1.x release first and fix any deprecation warnings.

## Removed modules

* `pekko-discovery-marathon-api` has been removed, because Apache Mesos has been retired. There is no replacement. If you still need it, stay on Pekko Management 1.x. ([#672](https://github.com/apache/pekko-management/issues/672))

## Deprecated modules

* `pekko-discovery-aws-api` uses the Amazon SDK v1, which has reached its end of life. The module has been deprecated since Pekko Management 1.2.1 and is still part of 2.0.x. Use `pekko-discovery-aws-api-async`, which uses the Amazon SDK v2, if it supports the discovery method that you need. See @ref:[AWS API](discovery/aws.md).

## Behavior changes

### TLS version

* `tls-version` is now the minimum TLS version that a client accepts. This applies to `pekko.discovery.kubernetes-api.tls-version`, `pekko.coordination.lease.kubernetes.tls-version` and `pekko.management.cluster.bootstrap.contact-point.http-client.tls-version`. In 1.x the value was the protocol that the `SSLContext` was created with, so the default of `TLSv1.2` did not enable TLS 1.3. In 2.x the default of `TLSv1.2` means that TLS 1.2 and TLS 1.3 can be negotiated and that older versions are refused. The minimum only narrows the TLS versions that the JVM enables for clients, it never enables more. ([PR930](https://github.com/apache/pekko-management/pull/930))
* The value has to be one of `TLSv1`, `TLSv1.1`, `TLSv1.2` or `TLSv1.3`. Generic `SSLContext` protocol names such as `TLS`, which 1.x accepted, are no longer accepted. Any other value, or a minimum that no enabled TLS version satisfies, fails with an `IllegalArgumentException` when the client is set up. ([PR930](https://github.com/apache/pekko-management/pull/930))
* Cluster Bootstrap: when `ca-path` is empty and `tls-version` is left at its default, contact points are still probed with the default client HTTPS context of Pekko HTTP, and the minimum is not applied to it. Setting `ca-path`, or a `tls-version` other than the default, makes the probes use a context of their own that the minimum does apply to. ([PR930](https://github.com/apache/pekko-management/pull/930))
* The `tlsVersion` accessors of the Kubernetes API discovery `Settings` and of the Cluster Bootstrap `contactPoint.httpClient` settings are deprecated. Use `minTlsVersion` instead. ([PR930](https://github.com/apache/pekko-management/pull/930))

### Pekko Management

* When the management HTTP server is stopped, it is now terminated gracefully. Requests that are in progress get up to `pekko.management.http.graceful-termination-timeout` (5 seconds by default) to complete, so stopping can take that much longer than in 1.x, where the server was only unbound. ([PR909](https://github.com/apache/pekko-management/pull/909))
* A warning is logged when the management HTTP endpoint is started without an authenticator. Configure one with `ManagementRouteProviderSettings.withAuth` if the endpoint can be reached by untrusted clients. ([PR934](https://github.com/apache/pekko-management/pull/934))
* Java API: `CheckResult.success()` is now empty when the health check failed. In 1.x it was always present, so code that used `success().isPresent()` treated a failed check as successful. `isSuccess()` is not affected. ([PR1032](https://github.com/apache/pekko-management/pull/1032))

### Cluster Bootstrap

* `SelfAwareJoinDecider` no longer waits indefinitely for the self contact point to be set. If it has not been set, for example because the bootstrap routes were wired manually rather than obtained from `ClusterBootstrap.routes`, deciding whether this node can join itself now fails with a `TimeoutException` instead of blocking a thread forever. This only bounds the wait for a missing self contact point. It does not detect a management HTTP endpoint that failed to bind, because the self contact point is set before the endpoint is bound. ([PR908](https://github.com/apache/pekko-management/pull/908))

### Kubernetes API discovery

* A request to the Kubernetes API fails with a `TimeoutException` when the response headers have not been received within the resolve timeout of the lookup. Reading the response body then has a resolve timeout of its own, so a lookup as a whole can take longer than one resolve timeout. ([PR915](https://github.com/apache/pekko-management/pull/915))
* `pekko.discovery.kubernetes-api.pod-namespace` is set from the `KUBERNETES_NAMESPACE` environment variable when that variable is defined. This takes precedence over the namespace read from `pod-namespace-path`. Check that the variable, if your deployment sets it, names the namespace that your pods run in. ([PR801](https://github.com/apache/pekko-management/pull/801))

### Kubernetes Lease

* A lease name that is longer than the maximum length is still truncated, but now gets a hash suffix that is derived from the full name. This changes the name of the Kubernetes lease resource for every lease whose name was truncated in 1.x, that is, whose name is longer than 63 characters after sanitization. Nodes on 1.x and nodes on 2.x then use different lease resources for the same lease, so they do not exclude each other during a rolling upgrade. Set `pekko.coordination.lease.kubernetes.on-truncate-add-hash-length = 0` to truncate names as 1.x did. The hash suffix does not change lease names that are not truncated. ([PR708](https://github.com/apache/pekko-management/pull/708))
* Lease names are lowercased with `Locale.ROOT`, where 1.x used the default locale of the JVM. With most default locales the names are the same. With a Turkish or Azerbaijani default locale, 1.x lowercased an uppercase `I` to a dotless `ı`, which was then dropped from the name, while 2.x keeps it as `i`. Such a lease name maps to a different Kubernetes lease resource in 2.x, whether or not it is truncated, and `on-truncate-add-hash-length = 0` does not change that. ([PR851](https://github.com/apache/pekko-management/pull/851))
* Before a rolling upgrade in which 1.x and 2.x nodes have to share leases, verify that both versions resolve your lease names to the same Kubernetes lease resources, for example by comparing the lease resources that a 2.x node creates in a test environment with the existing ones.
* A failed heartbeat is retried up to `heartbeat-max-retries` times (3 by default) within the heartbeat interval before the lease is given up, and a failed release is retried up to `release-max-retries` times (3 by default) within the `lease-operation-timeout`. Set them to `0` to give up on the first failure, as 1.x did. ([PR911](https://github.com/apache/pekko-management/pull/911))

### Consul discovery

* The module uses the [Kiwiproject Consul Client](https://github.com/kiwiproject/consul-client) (`org.kiwiproject:consul-client`) instead of the unmaintained `com.orbitz.consul:consul-client`. If your application also uses the Consul client classes directly, change the dependency and replace the `com.orbitz.consul` package with `org.kiwiproject.consul`. ([PR548](https://github.com/apache/pekko-management/pull/548))
* At most 8 services are looked up concurrently, where 1.x looked up all of them at once. The limit is set with `lookup-parallelism`. ([PR906](https://github.com/apache/pekko-management/pull/906))

### AWS discovery

* The AWS clients are closed during coordinated shutdown, in the `service-unbind` phase. ([PR907](https://github.com/apache/pekko-management/pull/907), [PR955](https://github.com/apache/pekko-management/pull/955))

## Configuration changes

### New configuration

Pekko Management:

* `pekko.management.http.graceful-termination-timeout` (default `5s`) is how long to wait for requests that are in progress when the management HTTP server is stopped. ([PR909](https://github.com/apache/pekko-management/pull/909))

Kubernetes Lease, under `pekko.coordination.lease.kubernetes`:

* `heartbeat-max-retries` (default `3`) and `release-max-retries` (default `3`). ([PR911](https://github.com/apache/pekko-management/pull/911))
* `lease-name-max-length` (default `63`) and `on-truncate-add-hash-length` (default `8`). ([PR708](https://github.com/apache/pekko-management/pull/708))

Consul discovery, under `pekko.discovery.pekko-consul`:

* `connect-timeout`, `read-timeout` and `write-timeout` (default `10s` each). ([PR906](https://github.com/apache/pekko-management/pull/906))
* `lookup-parallelism` (default `8`), which must be greater than 0. ([PR906](https://github.com/apache/pekko-management/pull/906), [PR956](https://github.com/apache/pekko-management/pull/956))
* `consul-token` (default `""`, which means no authentication). ([PR906](https://github.com/apache/pekko-management/pull/906))
* `tls-enabled` (default `false`) and `ca-path` (default `""`, which means that the default JVM trust store is used). ([PR906](https://github.com/apache/pekko-management/pull/906), [PR965](https://github.com/apache/pekko-management/pull/965))

Rolling updates:

* The new `pekko-rolling-update-kubernetes` module is configured under `pekko.rollingupdate.kubernetes`. See @ref:[Rolling Updates](rolling-updates.md).

### Removed configuration

* `pekko.discovery.marathon-api` was removed with the `pekko-discovery-marathon-api` module.
