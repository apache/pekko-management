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

export NAMESPACE=pekko-bootstrap-demo-ns
export APP_NAME=pekko-bootstrap-demo
export PROJECT_NAME=integration-test-kubernetes-api
export DEPLOYMENT=integration-test/kubernetes-api/kubernetes/pekko-cluster-watch.yml

# Forms the cluster and asserts that 3 MemberUp events were logged.
integration-test/scripts/kubernetes-test.sh

# The shared script only proves the cluster formed. Discovery falls back to list mode for any
# unrecognised api-poll-mode, so also prove the cluster formed off the watch stream.
echo "Checking that the watch stream was actually used..."
WATCHERS=0
for POD in $(kubectl get pods -n $NAMESPACE | grep $APP_NAME | grep Running | awk '{ print $1 }')
do
  if kubectl logs $POD -n $NAMESPACE | grep -q "Watch stream started for label selector"; then
    WATCHERS=$((WATCHERS + 1))
  fi
done

if [ $WATCHERS -eq 0 ]
then
  echo "=============================="
  echo "No pod logged a started watch stream, so discovery did not run in watch mode"
  exit 1
fi

echo "$WATCHERS pod(s) started a Kubernetes watch stream"
