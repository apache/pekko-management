/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.rollingupdate.kubernetes

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.actor.ClassicActorSystemProvider
import pekko.actor.ExtendedActorSystem
import pekko.actor.Extension
import pekko.actor.ExtensionId
import pekko.actor.ExtensionIdProvider
import pekko.event.Logging

final class PodDeletionCost(implicit system: ExtendedActorSystem) extends Extension {

  private val log = Logging(system, classOf[PodDeletionCost])
  private val configPath = "pekko.rollingupdate.kubernetes"
  private val config = system.settings.config.getConfig(configPath)
  private val k8sSettings = KubernetesSettings(config)
  private val costSettings = PodDeletionCostSettings(config)
  implicit private val ec: ExecutionContext = system.dispatcher

  log.debug("Settings {}", k8sSettings)

  private final val startStep = new AtomicBoolean(false)

  def start(): Unit = {
    if (k8sSettings.podName.isEmpty) {
      log.warning(
        "No configuration found to extract the pod name from. " +
        s"Be sure to provide the pod name with `$configPath.pod-name` " +
        "or by setting ENV variable `KUBERNETES_POD_NAME`.")
    } else if (startStep.compareAndSet(false, true)) {
      val props = KubernetesApiImpl(log, k8sSettings).map { kubernetesApi =>
        val crName =
          if (k8sSettings.customResourceSettings.enabled) {
            val name =
              k8sSettings.customResourceSettings.crName.getOrElse(KubernetesApi.makeDNS1039Compatible(system.name))
            log.info(
              "Starting PodDeletionCost for podName [{}], [{}] oldest will be written to CR [{}].",
              k8sSettings.podName,
              costSettings.annotatedPodsNr,
              name)
            Some(name)
          } else {
            log.info(
              "Starting PodDeletionCost for podName [{}], [{}] oldest will be annotated.",
              k8sSettings.podName,
              costSettings.annotatedPodsNr)
            None
          }
        PodDeletionCostAnnotator.props(k8sSettings, costSettings, kubernetesApi, crName)
      }

      props.foreach(system.systemActorOf(_, "podDeletionCostAnnotator"))
    } else log.warning("PodDeletionCost extension already initiated, yet start() method was called again. Ignoring.")
  }

  // autostart if the extension is loaded through the config extension list
  private val autostart =
    system.settings.config.getStringList("pekko.extensions").contains(classOf[PodDeletionCost].getName)

  if (autostart) {
    log.info("PodDeletionCost loaded through 'pekko.extensions' auto-starting itself.")
    try {
      start()
    } catch {
      case NonFatal(ex) =>
        log.error(ex, "Failed to autostart PodDeletionCost extension")
    }
  }
}

object PodDeletionCost extends ExtensionId[PodDeletionCost] with ExtensionIdProvider {

  override def lookup: PodDeletionCost.type = PodDeletionCost

  override def get(system: ActorSystem): PodDeletionCost = super.get(system)

  override def get(system: ClassicActorSystemProvider): PodDeletionCost = super.get(system)

  override def createExtension(system: ExtendedActorSystem): PodDeletionCost = new PodDeletionCost()(system)

}
