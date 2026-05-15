#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# license agreements; and to You under the Apache License, version 2.0:
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# This file is part of the Apache Pekko project, which was derived from Akka.
#
set -exu

export NAMESPACE=pekko-rolling-update-demo-cr-ns
export APP_NAME=pekko-rolling-update-demo
export PROJECT_NAME=integration-test-rolling-update-kubernetes
export CRD=rolling-update-kubernetes/pod-cost.yml
export DEPLOYMENT=integration-test/rolling-update-kubernetes/kubernetes/pekko-cluster-cr.yml

integration-test/scripts/rollingupdate-kubernetes-cr-test.sh
