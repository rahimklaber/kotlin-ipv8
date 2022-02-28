package nl.tudelft.ipv8.jvm.swap

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import mu.KotlinLogging
import nl.tudelft.ipv8.IPv8
import nl.tudelft.ipv8.IPv8Configuration
import nl.tudelft.ipv8.OverlayConfiguration
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.attestation.WalletAttestation
import nl.tudelft.ipv8.keyvault.JavaCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import nl.tudelft.ipv8.peerdiscovery.DiscoveryCommunity
import nl.tudelft.ipv8.peerdiscovery.strategy.PeriodicSimilarity
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomChurn
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import nl.tudelft.ipv8.util.hexToBytes
import org.bitcoinj.core.*
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.core.listeners.PeerDataEventListener
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes.*
import org.bitcoinj.store.*
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.stellar.sdk.KeyPair
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

data class PotentialOffer(val counterParty: Peer, val message: TradeMessage)

object ipv8Stuff {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logger = KotlinLogging.logger {}
    lateinit var ipv8: IPv8
    lateinit var swapCommunity: SwapCommunity
    lateinit var xlmKey: KeyPair
    lateinit var btcWallet : WalletAppKit
    lateinit var peerGroup: PeerGroup
    val myOffers = mutableListOf<TradeMessage>() // todo add sender of msg

    fun createSwap(recipient : String, amount: String) {
        val contract = Transaction(TestNet3Params())


        val lock = ScriptBuilder()
            .op(OP_IF)
            .number(0) // relative lock
            .op(OP_CHECKSEQUENCEVERIFY)
            .op(OP_DROP)
            .data(btcWallet.wallet().watchingKey.pubKey)
            .op(OP_CHECKSIGVERIFY)
            .op(OP_ELSE)
            .op(OP_SHA256)
            .data("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4".hexToBytes()) // for hi
            .op(OP_EQUALVERIFY)
            .data(btcWallet.wallet().watchingKey.pubKey)
            .op(OP_CHECKSIGVERIFY)
            .op(OP_ENDIF)
            .build()


        contract.addOutput(Coin.SATOSHI,lock)

        val potentialInputs = btcWallet.wallet().calculateAllSpendCandidates()
        println(potentialInputs.joinToString(" "))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val channel = BroadcastChannel<TradeMessage>(500)
    private fun createDiscoveryCommunity(): OverlayConfiguration<DiscoveryCommunity> {
        val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 20)
        val randomChurn = RandomChurn.Factory()
        val periodicSimilarity = PeriodicSimilarity.Factory()
        return OverlayConfiguration(
            DiscoveryCommunity.Factory(),
            listOf(randomWalk, randomChurn, periodicSimilarity)
        )
    }

    private fun createSwapCommunity(): OverlayConfiguration<SwapCommunity> {
        val randomWalk = RandomWalk.Factory(timeout = 3.0, peers = 20)
        return OverlayConfiguration(
            SwapCommunity.Factory(),
            listOf(randomWalk)
        )
    }

    fun getPeers() = swapCommunity.getPeers()


    suspend fun startIpv8() {
        val myKey = JavaCryptoProvider.generateKey()
        val myPeer = Peer(myKey)
        val udpEndpoint = UdpEndpoint(8090, InetAddress.getByName("0.0.0.0"))
        val endpoint = EndpointAggregator(udpEndpoint, null)

        val config = IPv8Configuration(
            overlays = listOf(
                createDiscoveryCommunity(),
                createSwapCommunity()
            ), walkerInterval = 1.0
        )

        ipv8 = IPv8(endpoint, config, myPeer)

        ipv8.start()

        swapCommunity = ipv8.getOverlay() ?: error("Cannot get swap community")
        scope.launch {
            while (ipv8.isStarted()) {
                delay(1000)
            }
            error("ipv8 engine STOPPED!")
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onMessage(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(TradeMessage)
        scope.launch {
            channel.send(payload.apply { })
        }
        logger.info {
            "received trade message from $peer :  $payload"
        }
    }

    suspend operator fun invoke() {
        xlmKey = if(PersistentStore.xlmKey == null || PersistentStore.xlmKey!!.isBlank()){
            val newSecret = String(KeyPair.random().secretSeed)
            PersistentStore.xlmKey = newSecret
            KeyPair.fromSecretSeed(newSecret)
        }else{
            KeyPair.fromSecretSeed(PersistentStore.xlmKey)
        }

        val walletdir = File("walletdir")
        btcWallet =  WalletAppKit(TestNet3Params(),walletdir,"")
        btcWallet.startAsync()

//        btcWallet = if(PersistentStore.btcKey == null || PersistentStore.btcKey!!.isBlank()){
//            val newMnemonic = Wallet.createDeterministic(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),Script.ScriptType.P2PKH).keyChainSeed.mnemonicString
//            PersistentStore.btcKey = newMnemonic
//            val seed = DeterministicSeed(newMnemonic,null,"",0) // ?? creation time?
//            WalletAppKit(TestNet3Params(),walletdir,"")
////            Wallet.fromSeed(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),seed,
////                Script.ScriptType.P2PKH
////            )
//        }else{
//            val seed = DeterministicSeed(PersistentStore.btcKey,null,"",0) // ?? creation time?
//            Wallet.fromSeed(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),seed,
//                Script.ScriptType.P2PKH
//            )
//        }
//        val store = MemoryFullPrunedBlockStore(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),1000)
//        val dbstore = LevelDBFullPrunedBlockStore(TestNet3Params(),"/btcdb.db",10000)
//        val spvfile = File("spvstore")
//        spvfile.createNewFile()
//        val spvstore = SPVBlockStore(TestNet3Params(), spvfile)
//        val chain = BlockChain(NetworkParameters.fromID(NetworkParameters.ID_TESTNET), btcWallet,spvstore)
//        peerGroup = PeerGroup(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),chain)
//        peerGroup.addWallet(btcWallet)
//        GlobalScope.launch(Dispatchers.IO) {
////            peerGroup.addAddress(InetSocketAddress("18.191.253.246",18333).address)
////            peerGroup.addAddress(InetSocketAddress("51.15.16.159",18333).address)
//            peerGroup.addAddress(InetSocketAddress("52.0.54.100",18333).address)
//            peerGroup.addAddress(InetSocketAddress("5.188.119.196",18333).address)
//            peerGroup.addAddress(InetSocketAddress("80.208.229.137",18333).address)
//            peerGroup.start()
//            println(peerGroup.connectedPeers)
//            delay(5000)
//            val tracker = DownloadProgressTracker()
//            peerGroup.startBlockChainDownload(tracker)
//        }
        withContext(scope.coroutineContext) {
            startIpv8()
        }
        swapCommunity.messageHandlers[TradeMessage.MESSAGE_ID] = ::onMessage

    }
}
