package nl.tudelft.ipv8.jvm.swap

import fr.acinq.bitcoin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import mu.KotlinLogging
import nl.tudelft.ipv8.IPv8
import nl.tudelft.ipv8.IPv8Configuration
import nl.tudelft.ipv8.OverlayConfiguration
import nl.tudelft.ipv8.Peer
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
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes.*
import org.bitcoinj.wallet.KeyChain
import org.bitcoinj.wallet.SendRequest
import org.stellar.sdk.KeyPair
import tornadofx.hex
import java.io.File
import java.net.InetAddress

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
            .data(btcWallet.wallet().currentKey(KeyChain.KeyPurpose.AUTHENTICATION).pubKey)
            .op(OP_CHECKSIGVERIFY)
            .op(OP_ELSE)
            .op(OP_SHA256)
            .data("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4".hexToBytes()) // for hi
            .op(OP_EQUALVERIFY)
            .data(btcWallet.wallet().currentKey(KeyChain.KeyPurpose.AUTHENTICATION).pubKey)
            .op(OP_CHECKSIGVERIFY)
            .op(OP_ENDIF)
            .build()


        contract.addOutput(Coin.parseCoin("0.0005"),lock)
//
//        val potentialInputs = btcWallet.wallet().calculateAllSpendCandidates()
//        contract.addInput(potentialInputs.first())
//        println(potentialInputs.first())
//        if(potentialInputs.first().value < Coin.parseCoin("0.00046227") ){
//            error("input value too low")
//        }
        val send = SendRequest.forTx(contract)
        btcWallet.wallet().completeTx(send)

        val future = btcWallet.peerGroup().broadcastTransaction(send.tx).broadcast().get()
        println(future)

////        btcWallet.wallet().completeTx()
//        println(potentialInputs.joinToString(" "))
    }

    fun tryToRefund(hash:String){
        val txWithOutput =  btcWallet.wallet().walletTransactions.find {
            it.transaction.txId.bytes.contentEquals("6c1b091d37c7230404d0ef1888eace4a266cdfcc14fc4514564d9926ebd542af".hexToBytes())
        }?.transaction ?: error("could not find tx")
//        val prevout = btcWallet.wallet().walletTransactions.find {
//            it.transaction.txId.bytes.contentEquals("55cdab5a9d93ad150d984bf8534cad5d4895d85424d110cb2396f2a9cfcf1d7e".hexToBytes())
//        }?.transaction?.outputs?.get(1) ?: throw Error("error can't find tx")
//        println(prevout)

//        val contract = Transaction(TestNet3Params())
//
//        contract.addOutput(Coin.SATOSHI, btcWallet.wallet().currentReceiveAddress())
        val signingKey = btcWallet.wallet().currentKey(KeyChain.KeyPurpose.AUTHENTICATION)
//        val unlockScript = ScriptBuilder()
//            .data(byteArrayOf(0))
//            .op(OP_1)
//            .build()
//
////        unlockScript.getScriptSigWithSignature()
////
////        scriptPubKey.getScriptSigWithSignature(
////            inputScript, signature.encodeToBitcoin(),
////            sigIndex
////        )
//
//        contract.addInput(prevout)
//
//
////        contract.add
//
//        val input = contract.inputs.first()
//
//
//        val signature: TransactionSignature = contract.calculateSignature(
//            0, signingKey, input.scriptBytes, Transaction.SigHash.ALL,
//            false
//        )
//
//        // at this point we have incomplete inputScript with OP_0 in place of one or more signatures. We
//        // already have calculated the signature using the local key and now need to insert it in the
//        // correct place within inputScript. For P2PKH and P2PK script there is only one signature and it
//        // always goes first in an inputScript (sigIndex = 0). In P2SH input scripts we need to figure out
//        // our relative position relative to other signers. Since we don't have that information at this
//        // point, and since we always run first, we have to depend on the other signers rearranging the
//        // signatures as needed. Therefore, always place as first signature.
//
//        // at this point we have incomplete inputScript with OP_0 in place of one or more signatures. We
//        // already have calculated the signature using the local key and now need to insert it in the
//        // correct place within inputScript. For P2PKH and P2PK script there is only one signature and it
//        // always goes first in an inputScript (sigIndex = 0). In P2SH input scripts we need to figure out
//        // our relative position relative to other signers. Since we don't have that information at this
//        // point, and since we always run first, we have to depend on the other signers rearranging the
//        // signatures as needed. Therefore, always place as first signature.
//        val sigIndex = 0
//        val updatedUnlock = ScriptBuilder()
//            .data(signature.encodeToBitcoin())
//            .op(OP_1)
//            .build()
////            unlockScript.getScriptSigWithSignature(
////            unlockScript, signature.encodeToBitcoin(),
////            sigIndex
////        )
//        input.scriptSig = updatedUnlock

        val prevTx = fr.acinq.bitcoin.Transaction.read(txWithOutput.bitcoinSerialize())
        val script = Script.parse(prevTx.txOut[1].publicKeyScript)
        println(script)
        println(prevTx.hash)

        val mypkh = btcWallet.wallet().currentReceiveAddress().hash

        val newtx = fr.acinq.bitcoin.Transaction(
            version = 1L,
            txIn =  listOf(
                TxIn(OutPoint(prevTx,1),signatureScript = listOf(),sequence = 0xFFFFFFFFL )
            ),
            txOut = listOf(
                TxOut(amount = 1000L.toSatoshi(), publicKeyScript = listOf(fr.acinq.bitcoin.OP_DUP, fr.acinq.bitcoin.OP_HASH160, OP_PUSHDATA(mypkh), fr.acinq.bitcoin.OP_EQUALVERIFY, fr.acinq.bitcoin.OP_CHECKSIG))
            ),
            lockTime = 0L
        )

        val kmpprivatekey = PrivateKey(signingKey.privKeyBytes)

        val sig = fr.acinq.bitcoin.Transaction.signInput(newtx,0,prevTx.txOut[1].publicKeyScript,SigHash.SIGHASH_ALL,kmpprivatekey)
        println("pubkey: ${kmpprivatekey.publicKey().toHex()}")
        println("sig: ${sig.hex}")
        val tx2 = newtx.updateSigScript(0, listOf(OP_PUSHDATA(sig), fr.acinq.bitcoin.OP_1))

        fr.acinq.bitcoin.Transaction.correctlySpends(tx2, listOf(prevTx),ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)
//        val tx =

//        val future = btcWallet.peerGroup().broadcastTransaction(contract).broadcast().get()
//        println(future)

//        val future = btcWallet.peerGroup().broadcastTransaction(contract).broadcast().get()
//        println(future)



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

