/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.management;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.http.javadsl.ConnectionContext;
import org.apache.pekko.http.javadsl.HttpsConnectionContext;
import org.apache.pekko.http.javadsl.server.directives.SecurityDirectives;
import org.apache.pekko.management.javadsl.PekkoManagement;

/** Compile-only, for documentation code snippets */
public class CodeExamples {
  ActorSystem system = null;

  public void start() {
    SSLContext sslContext = null;
    // #start-pekko-management-with-https-context
    PekkoManagement management = PekkoManagement.get(system);

    HttpsConnectionContext https = ConnectionContext.https(sslContext);
    management.start(settings -> settings.withHttpsConnectionContext(https));
    // #start-pekko-management-with-https-context
  }

  public void basicAuth() {
    PekkoManagement management = null;

    // #basic-auth
    final Function<
            Optional<SecurityDirectives.ProvidedCredentials>, CompletionStage<Optional<String>>>
        myUserPassAuthenticator =
            opt -> {
              if (opt.filter(c -> (c != null) && c.verify("p4ssw0rd")).isPresent()) {
                return CompletableFuture.completedFuture(Optional.of(opt.get().identifier()));
              } else {
                return CompletableFuture.completedFuture(Optional.empty());
              }
            };
    // ...
    management.start(settings -> settings.withAuth(myUserPassAuthenticator));
    // #basic-auth
  }

  public void stop() {
    // #stopping
    PekkoManagement httpClusterManagement = PekkoManagement.get(system);
    httpClusterManagement.start();
    // ...
    httpClusterManagement.stop();
    // #stopping
  }
}
