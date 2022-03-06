package nl.tudelft.ipv8.jvm.swap

import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import kotlinx.coroutines.*
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.keyvault.LibNaClPK
import nl.tudelft.ipv8.keyvault.LibNaClSK
import nl.tudelft.ipv8.logger
import nl.tudelft.ipv8.util.hexToBytes
import org.bitcoinj.core.Base58
import org.bitcoinj.core.Sha256Hash
import tornadofx.*
import java.util.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime


class AddTradeInfo(){
    val fromProp = SimpleStringProperty()
    val toProp = SimpleStringProperty()
    val fromAmountProp = SimpleStringProperty()
    val toAmountProp = SimpleStringProperty()
    var from by fromProp
    var to by toProp
    var fromAmount by fromAmountProp
    var toAmount by toAmountProp

    fun toTradeMessage(publicKey : LibNaClPK) = TradeMessage(
        from,
        to,
        fromAmount,
        toAmount,
        publicKey
    )
}

class MainPage : View() {
    val coscope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val tradeToAdd = AddTradeInfo()
    val xlmBalanceProp = SimpleStringProperty()
    var xlmBalance by xlmBalanceProp
    val btcBalanceProp = SimpleStringProperty()
    var btcBalance by btcBalanceProp

    init {
        xlmBalance = "xlm balance : 0"
        btcBalance = "btc balance : 0"
        runBlocking { ipv8Stuff() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val root = borderpane {
        top = hbox {
            label {
                this.text = "Trader"
            }
        }
        left = vbox {
            button("reload") {
                setOnAction {
                    coscope.launch {
                        try {
                            xlmBalance = "xlm balance :${
                                Config.client.accounts().account(
                                    ipv8Stuff.xlmKey.accountId
                                ).balances[0].balance
                            }"
                            btcBalance =
                                "btc balance : ${ipv8Stuff.btcWallet.wallet().balance.toFriendlyString()}"
                        }catch (e : Exception){
                            xlmBalance = "xlm balance : 0"
                        }
                    }
                }
            }
            text().bind(xlmBalanceProp)
            text("xlm addr: ${ipv8Stuff.xlmKey.accountId}") {
                onDoubleClick {
                    val clipboard: Clipboard = Clipboard.getSystemClipboard()
                    val content = ClipboardContent()
                    content.putString(ipv8Stuff.xlmKey.accountId)
                    clipboard.setContent(content)
                }
            }

            text().bind(btcBalanceProp)
            text(
                "btc addr: ${
                    Base58.encodeChecked(
                        111,
                        ipv8Stuff.btcWallet.wallet().currentReceiveAddress().hash
                    )
                }"
            ) {
                onDoubleClick {
                    val clipboard: Clipboard = Clipboard.getSystemClipboard()
                    val content = ClipboardContent()
                    val adrrstr =
                        Base58.encodeChecked(111, ipv8Stuff.btcWallet.wallet().currentReceiveAddress().hash)
                    content.putString(adrrstr)
                    clipboard.setContent(content)
                }
            }

            form() {
                fieldset("Add a trade") {
                    field("from coin") {
                        textfield().bind(tradeToAdd.fromProp)
                    }


                    field("to coin") {
                        textfield().bind(tradeToAdd.toProp)
                    }


                    field("from amount") {
                        textfield().bind(tradeToAdd.fromAmountProp)
                    }


                    field("to amount") {
                        textfield().bind(tradeToAdd.toAmountProp)
                     }
                 }

                button ("add"){
                    setOnAction {
                        val msg = tradeToAdd.toTradeMessage((ipv8Stuff.ipv8.myPeer.key as LibNaClSK).pub() as LibNaClPK)
                        ipv8Stuff.myOffers.add(msg)
                        ipv8Stuff.swapCommunity.broadcastMsg(msg)
                    }
                }

                val utx0hash = SimpleStringProperty()
                val privkeyhex = SimpleStringProperty()
                val index = SimpleIntegerProperty()

                fieldset("take back swap") {

                    field("privkey hex"){
                        textfield().bind(privkeyhex)
                    }

                    field("tx hash?"){
                        textfield().bind(utx0hash)
                    }

                    field("output index"){
                        textfield().bind(index)
                    }
                }

                button("try to refund"){
                    setOnAction {
                        coscope.launch{
                            val block = ipv8Stuff.btcWallet
                            ipv8Stuff.tryToRefund(utx0hash.get(),index.get(),privkeyhex.get())
                        }
                    }
                }
            }
        }
        center = vbox {
            tableview<TradeMessage> {
                coscope.launch {
                    val receiveChannel = ipv8Stuff.channel.openSubscription()
                    while(true){
                        val receivedMessage = receiveChannel.receive()
                        items.add(receivedMessage)
                    }
                }
                readonlyColumn("counterparty",TradeMessage::identity)
                readonlyColumn("from",TradeMessage::from)
                readonlyColumn("to",TradeMessage::to)
                readonlyColumn("fromAmount",TradeMessage::fromAmount)
                readonlyColumn("toAmount",TradeMessage::toAmount)
            }
            button("try swap") {setOnAction {
               coscope.launch {
                   ipv8Stuff.createSwap("","")
               }
            } }
        }
    }
}

class Application : App(MainPage::class)

private fun printPeersInfo(overlay: Overlay) {

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

        val avgPingStr =
            if (!avgPing.isNaN()) "" + (avgPing * 1000).roundToInt() + " ms" else "? ms"
        logger.info("${peer.mid} (S: ${lastRequestStr}, R: ${lastResponseStr}, ${avgPingStr})")
    }
}


@OptIn(ExperimentalTime::class)
suspend fun main(args: Array<String>) {
    launch<Application>(args)
//    ipv8Stuff()
//
//    GlobalScope.launch {
//        while (true) {
//            for ((_, overlay) in ipv8Stuff.ipv8.overlays) {
//                printPeersInfo(overlay)
//            }
//            logger.info("===")
//            delay(5000)
//        }
//    }
//
//    while (true){
//        // trade xlm btc 10 10
//        val input = readLine()?.split(" ")
//        when(input?.get(0)){
//            "trade" -> {
//                val from = input[1]
//                val to = input[2]
//                val fromAmount = input[3]
//                val toAmount = input[4]
//                ipv8Stuff.swapCommunity.broadcastMsg(TradeMessage(from, to, fromAmount, toAmount))
//            }
//        }
//    }
//
//
//    delay(Duration.INFINITE)

}
