import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rahimklaber.frosttestapp.ipv8.*
import me.rahimklaber.frosttestapp.ipv8.message.FrostMessage
import me.rahimklaber.frosttestapp.ipv8.message.StartKeyGenMsg
import mu.KotlinLogging
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.tftp.TFTPCommunity
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import nl.tudelft.ipv8.peerdiscovery.DiscoveryCommunity
import nl.tudelft.ipv8.peerdiscovery.strategy.PeriodicSimilarity
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomChurn
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import java.io.File
import java.io.PrintStream
import java.net.InetAddress
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.measureTimeMillis

object FIleLogger {
    private val file = File("logs.txt")
    val mutex = Mutex()

    init {
        file.createNewFile()
        file.writeText("")
//        close()
        System.setErr(PrintStream(file.outputStream()))
//        System.setOut(PrintStream(file.outputStream()))
    }

    fun close() {
        System.err.close()
    }

    suspend operator fun invoke(tolog: String) {
        mutex.withLock {
            file.appendText(tolog)
            file.appendText("\n")
        }
    }
}
suspend fun main(args: Array<String>) {

    val ipv8List = mutableListOf<IPv8>()
    val managers = mutableListOf<FrostManager>()
//    println(SignResult2::class)
    System.load("C:\\Users\\Rahim\\Desktop\\frostDaoExperimentPc\\frostDaoExperiment\\src\\lib\\rust_code.dll")
    FIleLogger("")
    var mainManager: FrostManager? = null
    var mainFrostCommunity: FrostCommunity? = null
    var next = 0
    var end = args[0].toInt()
    var checkPoint = min(10, end)
    suspend fun moreNodes() {
        for (i in next until checkPoint) {
            delay(1000)
            val ipv8 = startIpv8(9000 + i)
            ipv8List.add(ipv8)
            val frostCommunity = ipv8.getOverlay<FrostCommunity>()!!

            val manager =
                FrostManager(receiveChannel = frostCommunity.channel, networkManager = object : NetworkManager() {
                    override fun peers(): List<Peer> = frostCommunity.getPeers()
                    override suspend fun send(peer: Peer, msg: FrostMessage): Boolean {
                        FIleLogger("${frostCommunity.myPeer.mid}:  sending $msg; size: ${msg.serialize().size}")
                        val done = CompletableDeferred<Unit>(null)
                        val cbId = frostCommunity.addOnAck { peer, ack ->
                            if (ack.hashCode == msg.hashCode()) {
                                done.complete(Unit)
                            }
                        }

                        frostCommunity.sendForPublic(peer, msg)

                        for (i in 0..10) {
                            val x = select {
                                onTimeout(5000) {
//                                File("resending")
                                    frostCommunity.sendForPublic(peer, msg)
                                    false
                                }
                                done.onAwait {
                                    true
                                }
                            }
                            if (x) break

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
                        FIleLogger("${frostCommunity.myPeer.mid}:  broadcasting $msg; size: ${msg.serialize().size}")
                        val recipients = recipients.ifEmpty {
                            frostCommunity.getPeers()
                        }
                        val workScope = CoroutineScope(Dispatchers.Default)
                        val deferreds = recipients.map { peer ->
                            delay(8)
                            workScope.async {
                                val done = CompletableDeferred<Unit>(null)
                                val cbId = frostCommunity.addOnAck { ackSource, ack ->
                                    //todo, need to also check the peer when broadcasting
                                    if (ack.hashCode == msg.hashCode() && peer.mid == ackSource.mid) {

                                        done.complete(Unit)
                                    }
                                }

                                frostCommunity.sendForPublic(peer, msg)

                                for (i in 0..20) {
                                    val x = select {
                                        onTimeout(5000) {
//                                        println("resending")
                                            //todo what if this is the last iteration
                                            frostCommunity.sendForPublic(peer, msg)
                                            false
                                        }
                                        done.onAwait {
                                            true
                                        }
                                    }
                                    if (x) break

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

                })
            managers.add(manager)
            if (i == 0) {
                mainManager = manager
                mainFrostCommunity = frostCommunity
            }
        }
        ipv8List.forEach { curr ->
            val currCommunity = curr.getOverlay<FrostCommunity>()!!
            while (true) {
                val peers = currCommunity.getPeers()
                println("${curr.myPeer.mid} number of peers: ${peers.size}")
//            println("peers: $peers")
                println()
                if (peers.size >= checkPoint - 1) break
                delay(3000)
            }

        }
        next = checkPoint
        checkPoint = min(next + 10, end)

    }
    moreNodes()

//    ipv8List.forEach { curr ->
//        val currCommunity = curr.getOverlay<FrostCommunity>()!!
////        while (true) {
////            val peers = currCommunity.getPeers()
////            println("${curr.myPeer.mid} number of peers: ${peers.size}")
//////            println("peers: $peers")
////            println()
////            if (peers.size >= args[1].toInt() - 1)
////                break
////            delay(3000)
////        }
//        ipv8List.forEach { toadd->
//            if (curr.myPeer == toadd.myPeer)
//                return@forEach
//            currCommunity.addVerifiedPeer(toadd.myPeer)
//        }
//
//    }

    if (mainManager == null || mainFrostCommunity == null) {
        return
    }


    Runtime.getRuntime().addShutdownHook(Thread {
        println("shutting down")
        FIleLogger.close()
        ipv8List.forEach { it.stop() }
    })


    val file = File("output2.txt")
    file.createNewFile()
//        GlobalScope.launch { manager.updatesChannel.collect(::println) }


    println("waiting 10 seconds to connect to some nodes")

    delay(100)
    while (true){
        val curr = next - 10
        for (i in curr until (next -1)) {
//            println("peers: ${frostCommunity.getPeers().map { it.mid }}")
            println("${i}th keygen")
            val peer = mainFrostCommunity!!.getPeers().first { peer ->
                mainManager!!.frostInfo?.members?.find {
                    peer.mid == it.peer
                } == null
            }


            println("next peer: ${peer.mid}")
//            if (!) {
//                println("sending start msg failed")
//            }
            mainFrostCommunity!!.sendForPublic(peer, StartKeyGenMsg())
            val timeKeygen = measureTimeMillis {
                mainManager!!.updatesChannel.first {
                    it is Update.KeyGenDone
                }
            }
            delay(500)
            val data = Random.nextBytes(32)
            mainManager!!.proposeSignAsync(data)
            delay(100)
            val timeSign = measureTimeMillis {
                mainManager!!.updatesChannel.first {
                    it is Update.SignDone
                }
            }
            delay(100)
            managers.forEach {
                for ((_, job) in it.signJobs) {
                    if (job.isActive) {
                        FIleLogger("Sign job was active for peer ${it.networkManager.getMyPeer().mid}")
                        job.cancel()
                    }
                    it.state = FrostState.ReadyForSign
                }
                it.signJobs.clear()
            }

            file.appendText("${i + 2}, $timeKeygen, $timeSign\n")
            println("took $timeKeygen ms for ${i + 2} nodes")
        }
        moreNodes()
        println("next: $next")
        if (end < checkPoint)
            break
    }


}

private fun creatFrostCommunity(): OverlayConfiguration<FrostCommunity> {
    val randomWalk = RandomWalk.Factory(timeout = 30.0, peers = 100, windowSize = 50)
    return OverlayConfiguration(
        Overlay.Factory(FrostCommunity::class.java),
        listOf(randomWalk),
        100
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
    println(overlay::class.simpleName + ": ${peers.size} peers")
    for (peer in peers) {
        val avgPing = peer.getAveragePing()
        val lastRequest = peer.lastRequest
        val lastResponse = peer.lastResponse

        val lastRequestStr = if (lastRequest != null)
            "" + ((Date().time - lastRequest.time) / 1000.0).roundToInt() + " s" else "?"

        val lastResponseStr = if (lastResponse != null)
            "" + ((Date().time - lastResponse.time) / 1000.0).roundToInt() + " s" else "?"

        val avgPingStr = if (!avgPing.isNaN()) "" + (avgPing * 1000).roundToInt() + " ms" else "? ms"
        println("${peer.mid} (S: ${lastRequestStr}, R: ${lastResponseStr}, ${avgPingStr})")
    }
}

fun createTFT(): OverlayConfiguration<TFTPCommunity> {
    return OverlayConfiguration(
        Overlay.Factory(TFTPCommunity::class.java),
        listOf()
    )
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
//            createTFT()
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
//            println("===")
//            delay(5000)
//        }
//    }


    return ipv8

}
