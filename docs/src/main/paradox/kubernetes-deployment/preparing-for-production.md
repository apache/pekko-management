# Preparing for production

In preparation for production, we need to do two main things:

1. Write a Kubernetes deployment spec
1. Configure our Pekko cluster application 

The final configuration file and deployment spec are in the sample application.
In this guide we will show snippets. Locations of the samples:

* [Java](https://github.com/apache/pekko-samples/blob/main/pekko-sample-cluster-kubernetes-java) 
* [Scala](https://github.com/apache/pekko-samples/blob/main/pekko-sample-cluster-kubernetes-scala)

## Deployment Spec

Create a deployment spec in `kubernetes/pekko-cluster.yaml`. The following configuration uses:

* Application name / Actor system name: `appka`
* Namespace: `appka-1`

Change these for your application.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app: appka
  name: appka
  namespace: appka-1
spec:
  replicas: 2
  selector:
    matchLabels:
      app: appka
  template:
    metadata:
      labels:
        app: appka
    spec:
      containers:
      - name: appka
        image: pekko-sample-cluster-kubernetes-scala:latest
        readinessProbe:
          httpGet:
            path: /ready
            port: management
        livenessProbe:
          httpGet:
            path: /alive
            port: management
        ports:
        - name: management
          containerPort: 7626
          protocol: TCP
        - name: http
          containerPort: 8080
          protocol: TCP
        resources:
          limits:
            memory: 1024Mi
          requests:
            cpu: 2
            memory: 1024Mi
```
Here are a few things to note:

* We're using a Kubernetes deployment. Deployments are logical groupings of pods that represent a single service using the same template. 
  They support [configurable rolling updates](https://kubernetes.io/docs/tutorials/kubernetes-basics/update/update-intro/), 
  meaning the cluster will be gradually upgraded, rather than upgrading every node at once and incurring an outage.
* We label the pod in the `template` with `app: appka`. This must match the ActorSystem name so that @ref[Pekko Bootstrap](../bootstrap/index.md) finds the other nodes in the cluster.
* The image we're using is `pekko-sample-cluster-kubernetes:latest`. This corresponds to the name and version of the service in our build. 
  We will discuss how to select an appropriate version number below.
* We've only requested minimal CPU to the pods for this service. This is suitable for a local deployment, but you may wish to increase it if you're 
  deploying to a real deployment. Note that we also haven't set a CPU limit, this is because it's 
  [recommended that JVMs do not set a CPU limit](https://pekko.apache.org/docs/pekko/current/additional/deploying.html#resource-limits).
* We've configured a liveness probe and readiness probe. These are provided out of the box by Pekko Management and are discussed later.

## Image version number

We use a version tag of `latest` for the image. Not specifying a tag is the same as using the `latest` tag. We could have just specify
`pekko-sample-cluster-kubernetes`, and this would mean the same thing as `pekko-sample-cluster-kubernetes:latest`.

For production, the use of the `latest` tag is bad practice. 
For development, it's convenient and usually fine. We recommend if 
you're deploying to production, that you replace this with an actual version number that is updated each time you deploy. 
In the @ref[Building your application](building.md) section of this guide we will describe how to configure your build to base its 
version number off the current git commit hash, which is great especially for continuous deployment scenarios as it means a human doesn't 
need to be involved in selecting a unique version number. After building the image, you can take the version number generated in that step 
and update the image referenced in the spec accordingly.

## Rolling Updates and Scaling Down

Older versions of Kubernetes used to scale down the youngest nodes first and Pekko Singletons are usually deployed
on the oldest node (prior to Kubernetes 1.22).
This meant less disruption as the Singletons would only be moved once during a Rolling Update.
The Eclipse Ditto team have provided some documentation on how to adjust the Pod Deletion Cost to try to make the oldest node the
last one to be scaled down.
We hope to adjust Pekko Management to do this automatically in a future release.

The GitHub Issue is [#414](https://github.com/apache/pekko-management/issues/414) and this [comment](https://github.com/apache/pekko-management/issues/414#issuecomment-3112361378) contains the workaround.

