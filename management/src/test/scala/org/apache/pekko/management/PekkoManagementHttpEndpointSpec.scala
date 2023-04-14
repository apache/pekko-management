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

package org.apache.pekko.management

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.http.javadsl.server.directives.RouteAdapter
import pekko.http.scaladsl.{ ConnectionContext, Http, HttpsConnectionContext }
import pekko.http.scaladsl.model.{ HttpRequest, StatusCodes }
import pekko.http.scaladsl.model.headers.{ Authorization, BasicHttpCredentials }
import pekko.http.scaladsl.server.{ Directives, Route }
import pekko.http.scaladsl.server.directives.Credentials
import pekko.management.scaladsl.{ ManagementRouteProvider, ManagementRouteProviderSettings, PekkoManagement }
import pekko.testkit.SocketUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.InputStream
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class HttpManagementEndpointSpecRoutesScaladsl extends ManagementRouteProvider with Directives {
  override def routes(settings: ManagementRouteProviderSettings): Route =
    path("scaladsl") {
      get {
        complete("hello Scala")
      }
    }
}

class HttpManagementEndpointSpecRoutesJavadsl extends javadsl.ManagementRouteProvider with Directives {
  override def routes(settings: javadsl.ManagementRouteProviderSettings): org.apache.pekko.http.javadsl.server.Route =
    RouteAdapter {
      path("javadsl") {
        get {
          complete("hello Java")
        }
      }
    }
}

class PekkoManagementHttpEndpointSpec extends AnyWordSpecLike with Matchers {

  val config = ConfigFactory.parseString(
    """
      |pekko.remote.log-remote-lifecycle-events = off
      |pekko.remote.netty.tcp.port = 0
      |pekko.remote.artery.canonical.port = 0
      |#pekko.loglevel = DEBUG
    """.stripMargin)

  "Http Cluster Management" should {
    "start and stop" when {
      "not setting any security" in {
        val httpPort = SocketUtil.temporaryLocalPort()
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            //#management-host-port
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = 6262
            //#management-host-port
            pekko.management.http.port = $httpPort
            pekko.management.http.routes {
              test1 = "org.apache.pekko.management.HttpManagementEndpointSpecRoutesScaladsl"
              test2 = "org.apache.pekko.management.HttpManagementEndpointSpecRoutesJavadsl"
            }
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())

        val management = PekkoManagement(system)
        management.settings.Http.RouteProviders should contain(
          NamedRouteProvider("test1", "org.apache.pekko.management.HttpManagementEndpointSpecRoutesScaladsl"))
        management.settings.Http.RouteProviders should contain(
          NamedRouteProvider("test2", "org.apache.pekko.management.HttpManagementEndpointSpecRoutesJavadsl"))
        Await.result(management.start(), 10.seconds)

        val responseFuture1 = Http().singleRequest(HttpRequest(uri = s"http://127.0.0.1:$httpPort/scaladsl"))
        val response1 = Await.result(responseFuture1, 5.seconds)
        response1.status shouldEqual StatusCodes.OK
        Await.result(response1.entity.toStrict(3.seconds, 1000), 3.seconds).data.utf8String should ===("hello Scala")

        val responseFuture2 = Http().singleRequest(HttpRequest(uri = s"http://127.0.0.1:$httpPort/javadsl"))
        val response2 = Await.result(responseFuture2, 5.seconds)
        response2.status shouldEqual StatusCodes.OK
        Await.result(response2.entity.toStrict(3.seconds, 1000), 3.seconds).data.utf8String should ===("hello Java")

        try Await.ready(management.stop(), 5.seconds)
        finally system.terminate()
      }

      "setting basic authentication" in {
        val httpPort = SocketUtil.temporaryLocalPort()
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = $httpPort
            pekko.management.http.routes {
              test3 = "org.apache.pekko.management.HttpManagementEndpointSpecRoutesScaladsl"
            }
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())
        import system.dispatcher

        def myUserPassAuthenticator(credentials: Credentials): Future[Option[String]] =
          credentials match {
            case p @ Credentials.Provided(id) =>
              Future {
                // potentially
                if (p.verify("p4ssw0rd")) Some(id)
                else None
              }
            case _ => Future.successful(None)
          }

        val management = PekkoManagement(system)
        Await.result(management.start(_.withAuth(myUserPassAuthenticator)), 10.seconds)

        val httpRequest = HttpRequest(uri = s"http://127.0.0.1:$httpPort/scaladsl")
          .addHeader(Authorization(BasicHttpCredentials("user", "p4ssw0rd")))
        val responseGetMembersFuture = Http().singleRequest(httpRequest)
        val responseGetMembers = Await.result(responseGetMembersFuture, 5.seconds)

        responseGetMembers.status shouldEqual StatusCodes.OK

        try Await.ready(management.stop(), 5.seconds)
        finally system.terminate()
      }

      "setting ssl" in {
        val httpPort = SocketUtil.temporaryLocalPort()
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = $httpPort
            pekko.management.http.routes {
              test4 = "org.apache.pekko.management.HttpManagementEndpointSpecRoutesScaladsl"
            }
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())

        val password: Array[Char] = "password".toCharArray // do not store passwords in code, read them from somewhere safe!

        val ks: KeyStore = KeyStore.getInstance("PKCS12")
        val keystore: InputStream = getClass.getClassLoader.getResourceAsStream("httpsDemoKeys/keys/keystore.p12")

        require(keystore != null, "Keystore required!")
        ks.load(keystore, password)

        val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        keyManagerFactory.init(ks, password)

        val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        tmf.init(ks)

        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

        // A custom httpsClient for tests (without endpoint verification)
        val httpsClient: HttpsConnectionContext = ConnectionContext.httpsClient((host, port) => {
          val engine = sslContext.createSSLEngine(host, port)
          engine.setUseClientMode(true)
          // disable endpoint verification for tests
          engine.setSSLParameters {
            val params = engine.getSSLParameters
            params.setEndpointIdentificationAlgorithm(null)
            params
          }

          engine
        })

        // #start-pekko-management-with-https-context
        val management = PekkoManagement(system)

        val httpsServer: HttpsConnectionContext = ConnectionContext.httpsServer(sslContext)

        val started = management.start(_.withHttpsConnectionContext(httpsServer))
        // #start-pekko-management-with-https-context

        Await.result(started, 10.seconds)

        val httpRequest = HttpRequest(uri = s"https://127.0.0.1:$httpPort/scaladsl")
        val responseGetMembersFuture = Http().singleRequest(httpRequest, connectionContext = httpsClient)
        val responseGetMembers = Await.result(responseGetMembersFuture, 5.seconds)
        responseGetMembers.status shouldEqual StatusCodes.OK

        try Await.ready(management.stop(), 5.seconds)
        finally system.terminate()
      }

      "enable HealthCheckRoutes by default" in {
        val httpPort = SocketUtil.temporaryLocalPort()
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = $httpPort
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())

        val management = PekkoManagement(system)
        Await.result(management.start(), 10.seconds)

        val request1 = HttpRequest(uri = s"http://127.0.0.1:$httpPort/alive")
        val response1 = Await.result(Http().singleRequest(request1), 5.seconds)
        response1.status shouldEqual StatusCodes.OK

        val request2 = HttpRequest(uri = s"http://127.0.0.1:$httpPort/ready")
        val response2 = Await.result(Http().singleRequest(request2), 5.seconds)
        response2.status shouldEqual StatusCodes.OK

        try Await.ready(management.stop(), 5.seconds)
        finally system.terminate()
      }

      "bind random port" in {
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = 0
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())

        val management = PekkoManagement(system)
        val boundUri = Await.result(management.start(), 10.seconds)

        boundUri.effectivePort should not be 0
        boundUri.effectivePort should not be 80

        val request1 = HttpRequest(uri = s"http://127.0.0.1:${boundUri.effectivePort}/alive")
        val response1 = Await.result(Http().singleRequest(request1), 5.seconds)
        response1.status shouldEqual StatusCodes.OK

        val request2 = HttpRequest(uri = s"http://127.0.0.1:${boundUri.effectivePort}/ready")
        val response2 = Await.result(Http().singleRequest(request2), 5.seconds)
        response2.status shouldEqual StatusCodes.OK

        try Await.ready(management.stop(), 5.seconds)
        finally system.terminate()
      }

      "HealthCheckRoutes are disabled" in {
        val httpPort = SocketUtil.temporaryLocalPort()
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = $httpPort
            pekko.management.http.routes {
              health-checks = ""
            }
            # must have at least one route
            pekko.management.http.routes {
              test5 = "org.apache.pekko.management.HttpManagementEndpointSpecRoutesScaladsl"
            }
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())

        val management = PekkoManagement(system)
        Await.result(management.start(), 10.seconds)

        val request1 = HttpRequest(uri = s"http://127.0.0.1:$httpPort/alive")
        val response1 = Await.result(Http().singleRequest(request1), 5.seconds)
        response1.status shouldEqual StatusCodes.NotFound

        val request2 = HttpRequest(uri = s"http://127.0.0.1:$httpPort/ready")
        val response2 = Await.result(Http().singleRequest(request2), 5.seconds)
        response2.status shouldEqual StatusCodes.NotFound

        try Await.ready(management.stop(), 5.seconds)
        finally system.terminate()
      }
    }

    "not start" when {

      "no routes defined" in {
        val httpPort = SocketUtil.temporaryLocalPort()
        val configClusterHttpManager = ConfigFactory.parseString(
          s"""
            pekko.management.http.hostname = "127.0.0.1"
            pekko.management.http.port = $httpPort
            pekko.management.http.routes {
              health-checks = ""
            }
          """)

        implicit val system = ActorSystem("test", config.withFallback(configClusterHttpManager).resolve())

        val management = PekkoManagement(system)
        intercept[IllegalArgumentException] {
          Await.result(management.start(), 10.seconds)
        }.getCause.getMessage should include("No routes configured")

        system.terminate()
      }
    }
  }
}
