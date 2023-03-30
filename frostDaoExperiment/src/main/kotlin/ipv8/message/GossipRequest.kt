package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.deserializeLong
import nl.tudelft.ipv8.messaging.serializeLong


data class GossipRequest(
    val afterUnixTime: Long // unix time in seconds after which we want messages
) : Serializable {

    override fun serialize(): ByteArray {
        return serializeLong(afterUnixTime)
    }

    companion object Deserializer : Deserializable<GossipRequest>{
        const val MESSAGE_ID = 10;
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<GossipRequest, Int> {
            return GossipRequest(deserializeLong(buffer,offset)) to buffer.size
        }

    }
}

data class GossipResponse(
    val originallyFromMid: String,
    val payload: FrostMessage
) : Serializable{
    override fun serialize(): ByteArray {
        return (originallyFromMid.encodeToByteArray()
            .toList() + listOf(messageIdFromMsg(payload).toByte()) + payload.serialize().toList())
            .toByteArray()
    }
   companion object Deserializer: Deserializable<GossipResponse>{
       const val MESSAGE_ID = 11;
       override fun deserialize(buffer: ByteArray, offset: Int): Pair<GossipResponse, Int> {
            val midLength = 40
           val mid = buffer.copyOfRange(offset, offset + midLength).decodeToString()
           val msgId = buffer[offset+midLength].toInt()
           val msg = buffer.copyOfRange(offset+midLength+1,buffer.size)
           return GossipResponse(mid,deserializerFromId(msgId).deserialize(msg).first) to buffer.size
       }

   }
}