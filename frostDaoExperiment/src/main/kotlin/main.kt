import generated.SignResult2
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import me.rahimklaber.frosttestapp.ipv8.FrostCommunity
import me.rahimklaber.frosttestapp.ipv8.FrostManager
import me.rahimklaber.frosttestapp.ipv8.NetworkManager
import me.rahimklaber.frosttestapp.ipv8.message.FrostMessage
import me.rahimklaber.frosttestapp.ipv8.message.StartKeyGenMsg
import mu.KotlinLogging
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import nl.tudelft.ipv8.peerdiscovery.DiscoveryCommunity
import nl.tudelft.ipv8.peerdiscovery.strategy.PeriodicSimilarity
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomChurn
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import java.net.InetAddress
import java.util.*
import kotlin.math.roundToInt


suspend fun main() {
    println(SignResult2::class)
    System.load("C:\\Users\\Rahim\\Desktop\\frostDaoExperimentPc\\frostDaoExperiment\\src\\lib\\rust_code.dll")
    val ipv8 = startIpv8(8093)
    val frostCommunity = ipv8.getOverlay<FrostCommunity>()!!

    val manager = FrostManager(
        receiveChannel = frostCommunity.channel,
        networkManager = object : NetworkManager() {
            override fun peers(): List<Peer> = frostCommunity.getPeers()
            override suspend fun send(peer: Peer, msg: FrostMessage): Boolean {
                val done = CompletableDeferred<Unit>(null)
                val cbId = frostCommunity.addOnAck { peer, ack ->
                    if (ack.hashCode == msg.hashCode()) {
                        done.complete(Unit)
                    }
                }

                frostCommunity.sendForPublic(peer, msg)

                for (i in 0..5) {
                    val x = select {
                        onTimeout(1000) {
                            frostCommunity.sendForPublic(peer, msg)
                            false
                        }
                        done.onAwait {
                            true
                        }
                    }
                    if (x)
                        break

                }

                if (!done.isCompleted) {
                    // wait for 1 sec to see if a msg arrives
                    // deals with the case where the msgs timed out, but we resend it in the last iteration of the loop
                    delay(1000)
                }

                frostCommunity.removeOnAck(cbId)

                return done.isCompleted

            }

            override suspend fun broadcast(msg: FrostMessage, recipients: List<Peer>): Boolean {
                val recipients = recipients.ifEmpty {
                    frostCommunity.getPeers()
                }
                val workScope = CoroutineScope(Dispatchers.Default)
                val deferreds = recipients.map { peer ->
                    workScope.async {
                        val done = CompletableDeferred<Unit>(null)
                        val cbId = frostCommunity.addOnAck { ackSource, ack ->
                            //todo, need to also check the peer when broadcasting
                            if (ack.hashCode == msg.hashCode() && peer.mid == ackSource.mid) {

                                done.complete(Unit)
                            }
                        }

                        frostCommunity.sendForPublic(peer, msg)

                        for (i in 0..5) {
                            val x = select {
                                onTimeout(1000) {
                                    //todo what if this is the last iteration
                                    frostCommunity.sendForPublic(peer, msg)
                                    false
                                }
                                done.onAwait {
                                    true
                                }
                            }
                            if (x)
                                break

                        }
                        if (!done.isCompleted) {
                            // wait for 1 sec to see if a msg arrives
                            // deals with the case where the msgs timed out, but we resend it in the last iteration of the loop
                            delay(1000)
                        }
                        frostCommunity.removeOnAck(cbId)
                        done.isCompleted
                    }
                }

                for (deferred in deferreds) {
                    // failed
                    if (!deferred.await()) {
                        workScope.cancel()
                        return false
                    }
                }
                //success
                return true

            }

            override fun getMyPeer(): Peer = frostCommunity.myPeer

            override fun getPeerFromMid(mid: String): Peer =
                frostCommunity.getPeers().find { it.mid == mid } ?: error("Could not find peer")

        }
    )

    GlobalScope.launch(Dispatchers.IO) {
        while (true){
            when(val line = readln()){
                "keygen" -> {
                    val peer = frostCommunity.getPeers().first { peer ->
                        manager.frostInfo?.members?.find {
                            peer.mid == it.peer
                        } == null
                    }
                    frostCommunity.sendForPublic(peer, StartKeyGenMsg())
                }
            }
        }
    }

    manager.updatesChannel.collect(::println)
}

private fun creatFrostCommunity(): OverlayConfiguration<FrostCommunity> {
    val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 100)
    return OverlayConfiguration(
        Overlay.Factory(FrostCommunity::class.java),
        listOf(randomWalk)
    )
}

fun createDiscoveryCommunity(): OverlayConfiguration<DiscoveryCommunity> {
    val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 20)
    val randomChurn = RandomChurn.Factory()
    val periodicSimilarity = PeriodicSimilarity.Factory()
    return OverlayConfiguration(
        DiscoveryCommunity.Factory(),
        listOf(randomWalk, randomChurn, periodicSimilarity)
    )
}

private val logger = KotlinLogging.logger {}

fun printPeersInfo(overlay: Overlay) {
    val peers = overlay.getPeers()
    logger.info(overlay::class.simpleName + ": ${peers.size} peers")
    for (peer in peers) {
        val avgPing = peer.getAveragePing()
        val lastRequest = peer.lastRequest
        val lastResponse = peer.lastResponse

        val lastRequestStr = if (lastRequest != null)
            "" + ((Date().time - lastRequest.time) / 1000.0).roundToInt() + " s" else "?"

        val lastResponseStr = if (lastResponse != null)
            "" + ((Date().time - lastResponse.time) / 1000.0).roundToInt() + " s" else "?"

        val avgPingStr = if (!avgPing.isNaN()) "" + (avgPing * 1000).roundToInt() + " ms" else "? ms"
        logger.info("${peer.mid} (S: ${lastRequestStr}, R: ${lastResponseStr}, ${avgPingStr})")
    }
}

fun startIpv8(port: Int): IPv8 {
    val myKey = JavaCryptoProvider.generateKey()
    val myPeer = Peer(myKey)
    println("my mid: ${myPeer.mid}")
    val udpEndpoint = UdpEndpoint(port, InetAddress.getByName("0.0.0.0"))
    val endpoint = EndpointAggregator(udpEndpoint, null)

    val config = IPv8Configuration(
        overlays = listOf(
            creatFrostCommunity(),
//        createDiscoveryCommunity()
        ), walkerInterval = 1.0
    )

    val ipv8 = IPv8(endpoint, config, myPeer)
    ipv8.start()

//    GlobalScope.launch {
//        while (true) {
//            for ((_, overlay) in ipv8.overlays) {
//                printPeersInfo(overlay)
//            }
//            logger.info("===")
//            delay(5000)
//        }
//    }


    return ipv8

}
