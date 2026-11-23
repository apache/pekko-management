<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  license agreements; and to You under the Apache License, version 2.0:

    https://www.apache.org/licenses/LICENSE-2.0

  This file is part of the Apache Pekko project, which was derived from Akka.
-->

# Troubleshooting

## Logging

When troubleshooting cluster bootstrap, it could be helpful to enable `DEBUG` logging for:

* `org.apache.pekko.discovery`
* `org.apache.pekko.management.cluster.bootstrap`

## Kubernetes API `HTTP chunk size exceeds the configured limit` during contact point discovery 

Increase the max chunk size with:

```
pekko.http.client.parsing.max-chunk-size = 20m
```

This should only be necessary if the cluster size is 100s of nodes.
