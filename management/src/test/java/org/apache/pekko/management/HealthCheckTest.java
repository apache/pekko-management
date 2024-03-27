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

import static org.junit.Assert.assertEquals;

import com.typesafe.config.ConfigFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.BootstrapSetup;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.actor.setup.ActorSystemSetup;
import org.apache.pekko.management.javadsl.HealthChecks;
import org.apache.pekko.management.javadsl.LivenessCheckSetup;
import org.apache.pekko.management.javadsl.ReadinessCheckSetup;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

public class HealthCheckTest extends JUnitSuite {
  private static Throwable cause = new RuntimeException("oh dear");

  @SuppressWarnings("unused")
  public static class Ok implements Supplier<CompletionStage<Boolean>> {
    @Override
    public CompletionStage<Boolean> get() {
      return CompletableFuture.completedFuture(true);
    }
  }

  @SuppressWarnings("unused")
  public static class NotOk implements Supplier<CompletionStage<Boolean>> {
    public NotOk(ActorSystem system) {}

    @Override
    public CompletionStage<Boolean> get() {
      return CompletableFuture.completedFuture(false);
    }
  }

  @SuppressWarnings("unused")
  public static class Throws implements Supplier<CompletionStage<Boolean>> {
    public Throws(ActorSystem system) {}

    @Override
    public CompletionStage<Boolean> get() {
      return failed(cause);
    }
  }

  private static ExtendedActorSystem system = (ExtendedActorSystem) ActorSystem.create();

  @Test
  public void okReturnsTrue() throws Exception {
    List<NamedHealthCheck> healthChecks =
        Collections.singletonList(
            new NamedHealthCheck("Ok", "org.apache.pekko.management.HealthCheckTest$Ok"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks, healthChecks, "ready", "alive", java.time.Duration.ofSeconds(1)));
    assertEquals(true, checks.aliveResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.readyResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.alive().toCompletableFuture().get());
    assertEquals(true, checks.ready().toCompletableFuture().get());
  }

  @Test
  public void notOkayReturnsFalse() throws Exception {
    List<NamedHealthCheck> healthChecks =
        Collections.singletonList(
            new NamedHealthCheck("Ok", "org.apache.pekko.management.HealthCheckTest$Ok"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks, healthChecks, "ready", "alive", java.time.Duration.ofSeconds(1)));
    assertEquals(true, checks.aliveResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.readyResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.alive().toCompletableFuture().get());
    assertEquals(true, checks.ready().toCompletableFuture().get());
  }

  @Test
  public void throwsReturnsFailed() throws Exception {
    List<NamedHealthCheck> healthChecks =
        Collections.singletonList(
            new NamedHealthCheck("Throws", "org.apache.pekko.management.HealthCheckTest$Throws"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks, healthChecks, "ready", "alive", java.time.Duration.ofSeconds(1)));
    try {
      checks.alive().toCompletableFuture().get();
      Assert.fail("Expected exception");
    } catch (ExecutionException re) {
      assertEquals(cause, re.getCause().getCause());
    }
  }

  @Test
  public void defineViaActorSystemSetup() throws Exception {
    ReadinessCheckSetup readinessSetup =
        ReadinessCheckSetup.create(system -> Arrays.asList(new Ok(), new NotOk(system)));
    LivenessCheckSetup livenessSetup =
        LivenessCheckSetup.create(system -> Collections.singletonList(new NotOk(system)));
    // bootstrapSetup is needed for config (otherwise default config)
    BootstrapSetup bootstrapSetup = BootstrapSetup.create(ConfigFactory.parseString("some=thing"));
    ActorSystemSetup actorSystemSetup =
        ActorSystemSetup.create(bootstrapSetup, readinessSetup, livenessSetup);
    ExtendedActorSystem sys2 =
        (ExtendedActorSystem) ActorSystem.create("HealthCheckTest2", actorSystemSetup);
    try {
      HealthChecks checks =
          new HealthChecks(
              sys2,
              HealthCheckSettings.create(
                  Collections.emptyList(),
                  Collections.emptyList(),
                  "ready",
                  "alive",
                  java.time.Duration.ofSeconds(1)));
      assertEquals(false, checks.aliveResult().toCompletableFuture().get().isSuccess());
      assertEquals(false, checks.readyResult().toCompletableFuture().get().isSuccess());
      assertEquals(false, checks.alive().toCompletableFuture().get());
      assertEquals(false, checks.ready().toCompletableFuture().get());
    } finally {
      TestKit.shutdownActorSystem(sys2);
    }
  }

  @AfterClass
  public static void cleanup() {
    TestKit.shutdownActorSystem(system);
  }

  private static <R> CompletableFuture<R> failed(Throwable error) {
    CompletableFuture<R> future = new CompletableFuture<>();
    future.completeExceptionally(error);
    return future;
  }
}
