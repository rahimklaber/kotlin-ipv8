package nl.tudelft.ipv8.jvm.swap

import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.Key
import nl.tudelft.ipv8.keyvault.LibNaClPK
import nl.tudelft.ipv8.keyvault.PublicKey
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
private val lazySodium = LazySodiumJava(SodiumJava())
data class
TradeMessage(val from : String, val to : String, val fromAmount : String, val toAmount : String,val identity : PublicKey,) : Serializable {
    override fun serialize(): ByteArray {
        val msgString = "$from;$to;$fromAmount;$toAmount;${identity.keyToBin().toHex()}"
        println(msgString.toByteArray().toHex())
        return   msgString.toByteArray()
    }
    companion object Deserializer : Deserializable<TradeMessage> {
        const val MESSAGE_ID = 1
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<TradeMessage, Int> {
            val (from,to,fromAmount,toAmount,identity) = buffer.decodeToString().split(";")
            return Pair(TradeMessage(from,to,fromAmount, toAmount,LibNaClPK.fromBin(identity.hexToBytes(), lazySodium)), buffer.size)
        }
    }
}
