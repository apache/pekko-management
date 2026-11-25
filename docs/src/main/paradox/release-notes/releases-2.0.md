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
