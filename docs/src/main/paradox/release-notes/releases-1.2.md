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
