# Migration guide

## 1.0 

Version requirements:
* Pekko 1.0 later
* Pekko HTTP 1.0 or later

When migrating from Akka Management it is recommended to first upgrade to Akka 2.6.20 / Akka Management 1.1.4 before switching to Pekko/Pekko Management.
Please refer to the [Akka Management migration guide](https://doc.akka.io/docs/akka-management/current/migration.html).

### Management Port

The default port has changed from Akka's 8558 to Pekko's 7626.


### Kubernetes resources

The CRD has been adapted for Pekko.

For all your namespaces remove the leases
```
kubectl delete leases.akka.io --all -n <YOUR NAMSPACE>
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

To prepare for Pekko Management, setup the CRD and RBAC again following @ref[kubernetes-lease](kubernetes-lease.md).