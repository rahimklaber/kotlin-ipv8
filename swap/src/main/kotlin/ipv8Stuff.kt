package nl.tudelft.ipv8.jvm.swap

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Base58
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
import nl.tudelft.ipv8.util.toHex
import org.bitcoinj.core.*
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptOpCodes.*
import org.bitcoinj.wallet.KeyChain
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener
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
    lateinit var privateKey: PrivateKey

    fun createSwap(recipient : String, amount: String) {
        val contract = Transaction(TestNet3Params())

        val t= btcWallet.wallet().freshKey(KeyChain.KeyPurpose.AUTHENTICATION)
        println(t.privateKeyAsHex)

        privateKey = PrivateKey.fromHex(t.privateKeyAsHex)


        val lock = ScriptBuilder()
            .op(OP_IF)
            .number(1) // relative lock
            .op(OP_CHECKSEQUENCEVERIFY)
            .op(OP_DROP)
            .data(privateKey.publicKey().value.toByteArray())
            .op(OP_CHECKSIGVERIFY)
            .op(OP_ELSE)
            .op(OP_SHA256)
            .data("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4".hexToBytes()) // for hi
            .op(OP_EQUALVERIFY)
            .data(privateKey.publicKey().value.toByteArray())
            .op(OP_CHECKSIGVERIFY)
            .op(OP_ENDIF)
            .build()


        contract.addOutput(Coin.parseCoin("0.00025"),lock)
//
//        val potentialInputs = btcWallet.wallet().calculateAllSpendCandidates()
//        contract.addInput(potentialInputs.first())
//        println(potentialInputs.first())
//        if(potentialInputs.first().value < Coin.parseCoin("0.00046227") ){
//            error("input value too low")
//        }
//        contract.lo
        val send = SendRequest.forTx(contract)
        btcWallet.wallet().completeTx(send)

        val future = btcWallet.peerGroup().broadcastTransaction(send.tx).broadcast().get()
        println(future)

////        btcWallet.wallet().completeTx()
//        println(potentialInputs.joinToString(" "))
    }

    fun tryToRefund(hash:String,outputIndex:Int,privkeyHex: String){
        val txWithOutput =  btcWallet.wallet().walletTransactions.find {
            it.transaction.txId.bytes.contentEquals(hash.hexToBytes())
        }?.transaction ?: error("could not find tx")


        val key = ECKey.fromPrivate(privkeyHex.hexToBytes())

        println("imported key hex: ${key.privateKeyAsHex}")
        println("imported public key hex: ${key.publicKeyAsHex}")

        val contract = Transaction(TestNet3Params())
        contract.setVersion(2)
        val prevTxOut =txWithOutput.outputs[0]
        contract.addOutput(prevTxOut.value.div(10).multiply(9), btcWallet.wallet().currentReceiveAddress())
        contract.addInput(prevTxOut)
        contract.inputs[0].sequenceNumber = 0xFFFF0001L xor  (1 shl 31) xor (1 shl 22)
        val txHash = contract.hashForSignature(0, byteArrayOf(),Transaction.SigHash.ALL,false)
        val sig = contract.calculateSignature(0,key, byteArrayOf(),Transaction.SigHash.ALL,false)


        val redeemScript = ScriptBuilder()
            .op(OP_1)
            .data(sig.encodeToBitcoin())
            .op(OP_1)
            .build()
        contract.inputs[0].scriptSig = redeemScript

        println("tx hex: ${contract.toHexString()}")

        val future = btcWallet.peerGroup().broadcastTransaction(contract).broadcast()
        println(future.get())


//        val prevTx = fr.acinq.bitcoin.Transaction.read(txWithOutput.bitcoinSerialize())
//        val script = Script.parse(prevTx.txOut[outputIndex].publicKeyScript)
//        println(script)
//        println(prevTx.hash)
//
//        val mypkh = btcWallet.wallet().currentReceiveAddress().hash
//
//        val newtx = fr.acinq.bitcoin.Transaction(
//            version = 2L,
//            txIn =  listOf(
//                TxIn(OutPoint(prevTx,outputIndex.toLong()),signatureScript = listOf(),sequence = 0xFFFF0001L xor  (1 shl 31) xor (1 shl 22)  )
//            ),
//            txOut = listOf(
//                TxOut(amount = 1000L.toSatoshi(), publicKeyScript = listOf(fr.acinq.bitcoin.OP_DUP, fr.acinq.bitcoin.OP_HASH160, OP_PUSHDATA(mypkh), fr.acinq.bitcoin.OP_EQUALVERIFY, fr.acinq.bitcoin.OP_CHECKSIG))
//            ),
//            lockTime = 0L
//        )
//
//        val kmpprivatekey = PrivateKey.fromHex(privkeyHex)
//
//        val sig = fr.acinq.bitcoin.Transaction.signInput(newtx,0,prevTx.txOut[outputIndex].publicKeyScript,SigHash.SIGHASH_ALL,kmpprivatekey)
//        println("pubkey: ${kmpprivatekey.publicKey().toHex()}")
//        println("sig: ${sig.hex}")
////        val tx2 = newtx.updateSigScript(0, listOf(fr.acinq.bitcoin.OP_1,OP_PUSHDATA(sig),OP_PUSHDATA("hi".toByteArray()), fr.acinq.bitcoin.OP_0))
//        val tx2 = newtx.updateSigScript(0, listOf(fr.acinq.bitcoin.OP_1,OP_PUSHDATA(sig), fr.acinq.bitcoin.OP_1))
//
////        val tx =
//
//        println(
//            Transaction(
//                TestNet3Params(),
//                fr.acinq.bitcoin.Transaction.write(tx2)
//            ).toHexString()
//        )
//        println(fr.acinq.bitcoin.Transaction.write(tx2).toHex())
//        fr.acinq.bitcoin.Transaction.correctlySpends(tx2, listOf(prevTx),ScriptFlags.SCRIPT_VERIFY_CHECKSEQUENCEVERIFY)
//        val txTobroadcast = Transaction(TestNet3Params(),fr.acinq.bitcoin.Transaction.write(tx2))
//
//        val future = btcWallet.peerGroup().broadcastTransaction(txTobroadcast).broadcast().get()
//////
//        println(future)

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

