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
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.script.Script
import org.bitcoinj.store.H2FullPrunedBlockStore
import org.bitcoinj.store.MemoryBlockStore
import org.bitcoinj.store.MemoryFullPrunedBlockStore
import org.bitcoinj.store.PostgresFullPrunedBlockStore
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.stellar.sdk.KeyPair
import java.net.InetAddress
import java.net.InetSocketAddress

data class PotentialOffer(val counterParty: Peer, val message: TradeMessage)

object ipv8Stuff {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logger = KotlinLogging.logger {}
    lateinit var ipv8: IPv8
    lateinit var swapCommunity: SwapCommunity
    lateinit var xlmKey: KeyPair
    lateinit var btcWallet : Wallet
    val myOffers = mutableListOf<TradeMessage>() // todo add sender of msg

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

        btcWallet = if(PersistentStore.btcKey == null || PersistentStore.btcKey!!.isBlank()){
            val newMnemonic = Wallet.createDeterministic(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),Script.ScriptType.P2PKH).keyChainSeed.mnemonicString
            PersistentStore.btcKey = newMnemonic
            val seed = DeterministicSeed(newMnemonic,null,"",0) // ?? creation time?
            Wallet.fromSeed(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),seed,
                Script.ScriptType.P2PKH
            )
        }else{
            val seed = DeterministicSeed(PersistentStore.btcKey,null,"",0) // ?? creation time?
            Wallet.fromSeed(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),seed,
                Script.ScriptType.P2PKH
            )
        }
        val store = MemoryFullPrunedBlockStore(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),1000)
        val chain = BlockChain(NetworkParameters.fromID(NetworkParameters.ID_TESTNET), btcWallet,store)
        val peerGroup = PeerGroup(NetworkParameters.fromID(NetworkParameters.ID_TESTNET),chain)
        peerGroup.addWallet(btcWallet)
        GlobalScope.launch(Dispatchers.IO) {
            peerGroup.addAddress(InetSocketAddress("18.191.253.246",18333).address)
            peerGroup.start()
            println(peerGroup.connectedPeers)
            peerGroup.downloadBlockChain()
        }
        withContext(scope.coroutineContext) {
            startIpv8()
        }
        swapCommunity.messageHandlers[TradeMessage.MESSAGE_ID] = ::onMessage

    }
}

