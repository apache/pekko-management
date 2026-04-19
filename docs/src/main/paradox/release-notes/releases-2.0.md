# Release Notes (2.0.x)

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
