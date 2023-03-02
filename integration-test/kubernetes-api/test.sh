#!/bin/bash

set -exu

export NAMESPACE=pekko-bootstrap-demo-ns
export APP_NAME=pekko-bootstrap-demo
export PROJECT_NAME=integration-test-kubernetes-api
export DEPLOYMENT=integration-test/kubernetes-api/kubernetes/pekko-cluster.yml

integration-test/scripts/kubernetes-test.sh

