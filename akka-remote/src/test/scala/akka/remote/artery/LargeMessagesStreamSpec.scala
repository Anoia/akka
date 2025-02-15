/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.{ Actor, ActorRef, ActorSelection, Props, RootActorPath }
import akka.remote.{ RARP, RemoteActorRef }
import akka.testkit.JavaSerializable
import akka.testkit.TestProbe
import akka.util.ByteString

object LargeMessagesStreamSpec {
  case class Ping(payload: ByteString = ByteString.empty) extends JavaSerializable
  case class Pong(bytesReceived: Long) extends JavaSerializable

  class EchoSize extends Actor {
    def receive = {
      case Ping(bytes) => sender() ! Pong(bytes.size)
    }
  }
}

class LargeMessagesStreamSpec
    extends ArteryMultiNodeSpec("""
    akka {
      remote.artery.large-message-destinations = [ "/user/large" , "/user/largeWildcard*" ]
    }
  """.stripMargin) {

  import LargeMessagesStreamSpec._

  "The large message support" should {

    "not affect regular communication" in {
      val systemA = localSystem
      val systemB = newRemoteSystem()

      val senderProbeA = TestProbe()(systemA)
      val senderProbeB = TestProbe()(systemB)

      // start actor and make sure it is up and running
      val regular = systemB.actorOf(Props(new EchoSize), "regular")
      regular.tell(Ping(), senderProbeB.ref)
      senderProbeB.expectMsg(Pong(0))

      // communicate with it from the other system
      val regularRemote = awaitResolve(systemA.actorSelection(rootActorPath(systemB) / "user" / "regular"))
      regularRemote.tell(Ping(), senderProbeA.ref)
      senderProbeA.expectMsg(Pong(0))

      // flag should be cached now
      regularRemote.asInstanceOf[RemoteActorRef].cachedSendQueueIndex should be >= (Association.OrdinaryQueueIndex)

    }

    "pass small regular messages over the large-message stream" in {
      val systemA = localSystem
      val systemB = newRemoteSystem()

      val senderProbeA = TestProbe()(systemA)
      val senderProbeB = TestProbe()(systemB)

      // start actor and make sure it is up and running
      val large = systemB.actorOf(Props(new EchoSize), "large")
      large.tell(Ping(), senderProbeB.ref)
      senderProbeB.expectMsg(Pong(0))

      // communicate with it from the other system
      val addressB = RARP(systemB).provider.getDefaultAddress
      val rootB = RootActorPath(addressB)
      val largeRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "large"))
      largeRemote.tell(Ping(), senderProbeA.ref)
      senderProbeA.expectMsg(Pong(0))

      // flag should be cached now
      largeRemote.asInstanceOf[RemoteActorRef].cachedSendQueueIndex should ===(Association.LargeQueueIndex)

    }

    "accept wildcard suffixes in actor path" in {
      val systemA = localSystem
      val systemB = newRemoteSystem()

      val senderProbeA = TestProbe()(systemA)
      val senderProbeB = TestProbe()(systemB)

      // start actor and make sure it is up and running
      val large = systemB.actorOf(Props(new EchoSize), "largeWildcard123")
      large.tell(Ping(), senderProbeB.ref)
      senderProbeB.expectMsg(Pong(0))

      // communicate with it from the other system
      val addressB = RARP(systemB).provider.getDefaultAddress
      val rootB = RootActorPath(addressB)
      val largeRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "largeWildcard123"))
      largeRemote.tell(Ping(), senderProbeA.ref)
      senderProbeA.expectMsg(Pong(0))

      // flag should be cached now
      largeRemote.asInstanceOf[RemoteActorRef].cachedSendQueueIndex should ===(Association.LargeQueueIndex)
    }

    "allow for normal communication while simultaneously sending large messages" in {
      val systemA = localSystem
      val systemB = newRemoteSystem()

      val senderProbeB = TestProbe()(systemB)

      // setup two actors, one with the large flag and one regular
      val large = systemB.actorOf(Props(new EchoSize), "large")
      large.tell(Ping(), senderProbeB.ref)
      senderProbeB.expectMsg(Pong(0))

      val regular = systemB.actorOf(Props(new EchoSize), "regular")
      regular.tell(Ping(), senderProbeB.ref)
      senderProbeB.expectMsg(Pong(0))

      // both up and running, resolve remote refs
      val addressB = RARP(systemB).provider.getDefaultAddress
      val rootB = RootActorPath(addressB)
      val largeRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "large"))
      val regularRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "regular"))

      // send a large message, as well as some regular ones
      val probeSmall = TestProbe()(systemA)
      val probeLarge = TestProbe()(systemA)

      val largeBytes = 2000000
      largeRemote.tell(Ping(ByteString.fromArray(new Array[Byte](largeBytes))), probeLarge.ref)
      regularRemote.tell(Ping(), probeSmall.ref)
      Thread.sleep(50)
      regularRemote.tell(Ping(), probeSmall.ref)
      Thread.sleep(50)
      regularRemote.tell(Ping(), probeSmall.ref)

      // should be no problems sending regular small messages while large messages are being sent
      probeSmall.expectMsg(Pong(0))
      probeSmall.expectMsg(Pong(0))
      probeSmall.expectMsg(Pong(0))
      probeLarge.expectMsg(10.seconds, Pong(largeBytes))

      // cached flags should be set now
      largeRemote.asInstanceOf[RemoteActorRef].cachedSendQueueIndex should ===(Association.LargeQueueIndex)
      regularRemote.asInstanceOf[RemoteActorRef].cachedSendQueueIndex should be >= (Association.OrdinaryQueueIndex)
    }
  }

  def awaitResolve(selection: ActorSelection): ActorRef = {
    awaitAssert {
      Await.result(selection.resolveOne(1.second), 1.seconds)
    }
  }
}
