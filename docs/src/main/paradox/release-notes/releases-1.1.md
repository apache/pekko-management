# Release Notes (1.1.x)

## 1.1.1

Release notes for Apache Pekko Management 1.1.1. This release includes a security fix.

### Security fix

* CVE-2025-46548: If you enable Basic Authentication in Pekko Management using the Java DSL, the authenticator may not be properly applied ([PR418](https://github.com/apache/pekko-management/pull/418))

If you have configured Pekko Management to use Basic Authentication then you should consider upgrading to this version or a newer one.

### Additions

* Configuration option to enable gzip compression on k8s pods api for service discovery ([PR336](https://github.com/apache/pekko-management/pull/336))

### Dependency Upgrades

* Pekko 1.1.3 ([PR374](https://github.com/apache/pekko-management/pull/374))

### Infrastructure

* Exclude project report from link validator ([PR348](https://github.com/apache/pekko-management/pull/348))
* Use PekkoCoreDependency ([PR341](https://github.com/apache/pekko-management/pull/341))
* CI setup-sbt ([PR333](https://github.com/apache/pekko-management/pull/333))

## 1.1.0

Release notes for Apache Pekko Management 1.1.0. See [GitHub Milestone for 1.1.0-M1](https://github.com/apache/pekko-management/milestone/1?closed=1) and [GitHub Milestone for 1.1.0](https://github.com/apache/pekko-management/milestone/2?closed=1) for a fuller list of changes.

### Additions

* Support Kubernetes Native Leases ([PR217](https://github.com/apache/pekko-management/pull/217))
* Add pekko-management-bom (Bill of Materials) ([PR244](https://github.com/apache/pekko-management/pull/244))
* Add startup checks ([PR293](https://github.com/apache/pekko-management/pull/293)) (not in v1.1.0-M1)
* Support Kubernetes Service Discovery Custom Settings ([PR313](https://github.com/apache/pekko-management/pull/313)) (not in v1.1.0-M1)

### Dependency Upgrades

* switch to slf4j v2 ([PR193](https://github.com/apache/pekko-management/pull/193))
