#!/bin/bash

# For now this assumed there is a minikube running locally, with kubectl on the path.

set -exu

JOB_NAME=lease-test
PROJECT_DIR=lease-kubernetes-int-test
JOB_YML=$1

eval $(minikube -p minikube docker-env)
sbt "lease-kubernetes-int-test / docker:publishLocal"

kubectl apply -f "$PROJECT_DIR/kubernetes/rbac.yml"
kubectl delete -f "$PROJECT_DIR/kubernetes/$JOB_YML" || true

for i in {1..10}
do
  echo "Waiting for old jobs to be deleted"
  [ `kubectl get jobs | grep $JOB_NAME | wc -l` -eq 0 ] && break
  sleep 3
done

echo "Old jobs cleaned up. Creating new job"

kubectl create -f "$PROJECT_DIR/kubernetes/$JOB_YML"

# Add in a default sleep when we know a min amount of time it'll take

# Waiting for the status failed or complete to occur
echo "Checking for job completion"
kubectl wait --for=jsonpath='{.status.conditions[0].status}'=True job/$JOB_NAME

echo "Logs for job run:"
echo "=============================="

pods=$(kubectl get pods --selector=job-name=$JOB_NAME --output=jsonpath={.items..metadata.name})
echo "Pods: $pods"
for pod in $pods
do
  echo "Logging for $pod"
  kubectl logs $pod
done

if [ $(kubectl get job/$JOB_NAME -o jsonpath='{.status.conditions[0].type}') = "Failed" ]
then
  kubectl get jobs
  kubectl describe job $JOB_NAME
  echo "[ERROR] Job did not complete successfully. See logs above."
  exit -1
fi


