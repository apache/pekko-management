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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.BootstrapSetup;
import org.apache.pekko.actor.ClassicActorSystemProvider;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.actor.setup.ActorSystemSetup;
import org.apache.pekko.http.javadsl.model.Uri;
import org.apache.pekko.management.javadsl.CheckResult;
import org.apache.pekko.management.javadsl.HealthChecks;
import org.apache.pekko.management.javadsl.LivenessCheckSetup;
import org.apache.pekko.management.javadsl.ManagementRouteProviderSettings;
import org.apache.pekko.management.javadsl.ManagementRouteProviderSettingsImpl;
import org.apache.pekko.management.javadsl.ReadinessCheckSetup;
import org.apache.pekko.management.javadsl.StartupCheckSetup;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HealthCheckTest {
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
        List.of(new NamedHealthCheck("Ok", "org.apache.pekko.management.HealthCheckTest$Ok"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks,
                healthChecks,
                healthChecks,
                "startup",
                "ready",
                "alive",
                java.time.Duration.ofSeconds(1)));
    assertEquals(true, checks.startupResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.aliveResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.readyResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.startup().toCompletableFuture().get());
    assertEquals(true, checks.alive().toCompletableFuture().get());
    assertEquals(true, checks.ready().toCompletableFuture().get());
  }

  @Test
  public void notOkayReturnsFalse() throws Exception {
    List<NamedHealthCheck> healthChecks =
        List.of(new NamedHealthCheck("NotOk", "org.apache.pekko.management.HealthCheckTest$NotOk"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks,
                healthChecks,
                healthChecks,
                "startup",
                "ready",
                "alive",
                java.time.Duration.ofSeconds(1)));
    assertEquals(false, checks.startupResult().toCompletableFuture().get().isSuccess());
    assertEquals(false, checks.aliveResult().toCompletableFuture().get().isSuccess());
    assertEquals(false, checks.readyResult().toCompletableFuture().get().isSuccess());
    assertEquals(false, checks.startup().toCompletableFuture().get());
    assertEquals(false, checks.alive().toCompletableFuture().get());
    assertEquals(false, checks.ready().toCompletableFuture().get());
  }

  @Test
  public void checkResultReflectsSuccess() throws Exception {
    CheckResult result =
        checksFor("Ok", "org.apache.pekko.management.HealthCheckTest$Ok")
            .readyResult()
            .toCompletableFuture()
            .get();
    assertEquals(true, result.isSuccess());
    assertTrue(result.success().isPresent());
    assertFalse(result.failure().isPresent());
  }

  @Test
  public void checkResultReflectsFailure() throws Exception {
    CheckResult result =
        checksFor("NotOk", "org.apache.pekko.management.HealthCheckTest$NotOk")
            .readyResult()
            .toCompletableFuture()
            .get();
    assertEquals(false, result.isSuccess());
    assertFalse(result.success().isPresent());
    assertTrue(result.failure().isPresent());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void creatableThroughLegacyConstructor() throws Exception {
    List<NamedHealthCheck> healthChecks =
        List.of(new NamedHealthCheck("Ok", "org.apache.pekko.management.HealthCheckTest$Ok"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks, healthChecks, "ready", "alive", java.time.Duration.ofSeconds(1)));
    assertEquals(true, checks.startupResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.aliveResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.readyResult().toCompletableFuture().get().isSuccess());
    assertEquals(true, checks.startup().toCompletableFuture().get());
    assertEquals(true, checks.alive().toCompletableFuture().get());
    assertEquals(true, checks.ready().toCompletableFuture().get());
  }

  @Test
  public void throwsReturnsFailed() throws Exception {
    List<NamedHealthCheck> healthChecks =
        List.of(
            new NamedHealthCheck("Throws", "org.apache.pekko.management.HealthCheckTest$Throws"));
    HealthChecks checks =
        new HealthChecks(
            system,
            HealthCheckSettings.create(
                healthChecks,
                healthChecks,
                healthChecks,
                "startup",
                "ready",
                "alive",
                java.time.Duration.ofSeconds(1)));
    try {
      checks.alive().toCompletableFuture().get();
      Assertions.fail("Expected exception");
    } catch (ExecutionException re) {
      assertEquals(cause, re.getCause().getCause());
    }
  }

  @Test
  public void defineViaActorSystemSetup() throws Exception {
    StartupCheckSetup startupCheckSetup =
        StartupCheckSetup.create(system -> List.of(new NotOk(system)));
    ReadinessCheckSetup readinessSetup =
        ReadinessCheckSetup.create(system -> List.of(new Ok(), new NotOk(system)));
    LivenessCheckSetup livenessSetup =
        LivenessCheckSetup.create(system -> List.of(new NotOk(system)));
    // bootstrapSetup is needed for config (otherwise default config)
    BootstrapSetup bootstrapSetup = BootstrapSetup.create(ConfigFactory.parseString("some=thing"));
    ActorSystemSetup actorSystemSetup =
        ActorSystemSetup.create(bootstrapSetup, startupCheckSetup, readinessSetup, livenessSetup);
    ExtendedActorSystem sys2 =
        (ExtendedActorSystem) ActorSystem.create("HealthCheckTest2", actorSystemSetup);
    try {
      HealthChecks checks =
          new HealthChecks(
              sys2,
              HealthCheckSettings.create(
                  Collections.emptyList(),
                  Collections.emptyList(),
                  Collections.emptyList(),
                  "startup",
                  "ready",
                  "alive",
                  java.time.Duration.ofSeconds(1)));
      assertEquals(false, checks.startupResult().toCompletableFuture().get().isSuccess());
      assertEquals(false, checks.aliveResult().toCompletableFuture().get().isSuccess());
      assertEquals(false, checks.readyResult().toCompletableFuture().get().isSuccess());
      assertEquals(false, checks.startup().toCompletableFuture().get());
      assertEquals(false, checks.alive().toCompletableFuture().get());
      assertEquals(false, checks.ready().toCompletableFuture().get());
    } finally {
      TestKit.shutdownActorSystem(sys2);
    }
  }

  public static class NotOkFuture implements Supplier<CompletableFuture<Boolean>> {
    @Override
    public CompletableFuture<Boolean> get() {
      return CompletableFuture.completedFuture(false);
    }
  }

  @Test
  public void setupsAcceptWiderFunctionTypes() throws Exception {
    Function<ClassicActorSystemProvider, ArrayList<NotOkFuture>> createHealthChecks =
        system -> new ArrayList<>(List.of(new NotOkFuture()));
    ActorSystemSetup actorSystemSetup =
        ActorSystemSetup.create(
            BootstrapSetup.create(ConfigFactory.parseString("some=thing")),
            StartupCheckSetup.create(createHealthChecks),
            ReadinessCheckSetup.create(createHealthChecks),
            LivenessCheckSetup.create(createHealthChecks));
    ExtendedActorSystem sys2 =
        (ExtendedActorSystem) ActorSystem.create("HealthCheckTest3", actorSystemSetup);
    try {
      HealthChecks checks =
          new HealthChecks(
              sys2,
              HealthCheckSettings.create(
                  Collections.emptyList(),
                  Collections.emptyList(),
                  Collections.emptyList(),
                  "startup",
                  "ready",
                  "alive",
                  java.time.Duration.ofSeconds(1)));
      assertEquals(false, checks.startup().toCompletableFuture().get());
      assertEquals(false, checks.alive().toCompletableFuture().get());
      assertEquals(false, checks.ready().toCompletableFuture().get());
    } finally {
      TestKit.shutdownActorSystem(sys2);
    }
  }

  @Test
  public void setupsReturnReadOnlyCopyOfHealthChecks() {
    NotOkFuture check = new NotOkFuture();
    ArrayList<NotOkFuture> source = new ArrayList<>(List.of(check));
    Function<ActorSystem, ArrayList<NotOkFuture>> createHealthChecks = system -> source;
    List<Function<ActorSystem, List<Supplier<CompletionStage<Boolean>>>>> setups =
        List.of(
            StartupCheckSetup.create(createHealthChecks).createHealthChecks(),
            ReadinessCheckSetup.create(createHealthChecks).createHealthChecks(),
            LivenessCheckSetup.create(createHealthChecks).createHealthChecks());
    for (Function<ActorSystem, List<Supplier<CompletionStage<Boolean>>>> setup : setups) {
      source.clear();
      source.add(check);
      List<Supplier<CompletionStage<Boolean>>> healthChecks = setup.apply(system);
      assertThrows(UnsupportedOperationException.class, () -> healthChecks.add(new Ok()));
      source.clear();
      assertEquals(1, healthChecks.size());
      assertSame(check, healthChecks.get(0));
    }
  }

  @Test
  public void setupsAcceptIterableOfHealthChecks() {
    NotOkFuture check = new NotOkFuture();
    Function<ActorSystem, Iterable<NotOkFuture>> createHealthChecks =
        system -> Collections.singleton(check);
    List<Function<ActorSystem, List<Supplier<CompletionStage<Boolean>>>>> setups =
        List.of(
            StartupCheckSetup.create(createHealthChecks).createHealthChecks(),
            ReadinessCheckSetup.create(createHealthChecks).createHealthChecks(),
            LivenessCheckSetup.create(createHealthChecks).createHealthChecks());
    for (Function<ActorSystem, List<Supplier<CompletionStage<Boolean>>>> setup : setups) {
      List<Supplier<CompletionStage<Boolean>>> healthChecks = setup.apply(system);
      assertEquals(1, healthChecks.size());
      assertSame(check, healthChecks.get(0));
    }
  }

  @Test
  public void withAuthAcceptsWiderFunctionType() throws Exception {
    Optional<String> principal = Optional.of("principal");
    Function<Object, CompletableFuture<Optional<String>>> auth =
        credentials -> CompletableFuture.completedFuture(principal);
    ManagementRouteProviderSettingsImpl settings =
        (ManagementRouteProviderSettingsImpl)
            ManagementRouteProviderSettings.create(Uri.create("http://localhost")).withAuth(auth);
    assertEquals(
        principal,
        settings.javadslAuth().get().apply(Optional.empty()).toCompletableFuture().get());
  }

  @AfterAll
  public static void cleanup() {
    TestKit.shutdownActorSystem(system);
  }

  private static HealthChecks checksFor(String name, String fqcn) {
    List<NamedHealthCheck> healthChecks = List.of(new NamedHealthCheck(name, fqcn));
    return new HealthChecks(
        system,
        HealthCheckSettings.create(
            healthChecks,
            healthChecks,
            healthChecks,
            "startup",
            "ready",
            "alive",
            java.time.Duration.ofSeconds(1)));
  }

  private static <R> CompletableFuture<R> failed(Throwable error) {
    CompletableFuture<R> future = new CompletableFuture<>();
    future.completeExceptionally(error);
    return future;
  }
}
