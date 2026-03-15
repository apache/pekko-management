# Release Notes (1.2.x)

## 1.2.1

Release notes for Apache Pekko Management 1.2.1. See [GitHub Milestone for 1.2.1](https://github.com/apache/pekko-management/milestone/8?closed=1) for a fuller list of changes.

### Bug Fix

* cluster-bootstrap: add back support for using the JRE truststore for HTTPS requests ([PR653](https://github.com/apache/pekko-management/pull/653))

### Changes

* Deprecate pekko-discovery-aws-api module because Amazon SDK v1 has reached End of Life

### Dependency Upgrades

* Amazon SDK 2.42.2
* Scala 2.12.21, 2.13.18 and 3.3.7

## 1.2.0

Release notes for Apache Pekko Management 1.2.0. See [GitHub Milestone for 1.2.0](https://github.com/apache/pekko-management/milestone/5?closed=1) for a fuller list of changes.

### Additions

* Configurable TLS version ([PR456](https://github.com/apache/pekko-management/pull/456))
* cluster-bootstrap now supports TLS requests in client calls ([PR549](https://github.com/apache/pekko-management/pull/549))
* Pekko SBR Native Lease Management now supports token rotation ([#543](https://github.com/apache/pekko-management/issues/543))
