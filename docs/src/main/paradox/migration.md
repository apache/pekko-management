<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  license agreements; and to You under the Apache License, version 2.0:

    https://www.apache.org/licenses/LICENSE-2.0

  This file is part of the Apache Pekko project, which was derived from Akka.
-->

# Migration guide

## 2.0

See @ref:[Migration from Apache Pekko Management 1.x to 2.x](migration-guide-1.x-2.x.md).

## 1.0

When migrating from Akka Management it is recommended to first upgrade to Akka 2.6.20 / Akka Management 1.1.4 before switching to Pekko/Pekko Management.
Please refer to the [Akka Management migration guide](https://doc.akka.io/docs/akka-management/current/migration.html).

### Management Port

The default port has changed from Akka's 8558 to Pekko's 7626.


### Kubernetes resources

The CRD has been adapted for Pekko.

For all your namespaces remove the leases
```
kubectl delete leases.akka.io --all -n <YOUR NAMESPACE>
```

And RBAC
```bash
kubectl delete role akka-lease-access
kubectl delete sa akka-cluster
kubectl delete rb akka-cluster-lease-access
```

And finally the CRD:
```
kubectl delete crd leases.akka.io
```

To prepare for Pekko Management, set up the CRD and RBAC again following @ref[kubernetes-lease](kubernetes-lease.md).