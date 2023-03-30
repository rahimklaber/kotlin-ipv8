package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.deserializeUInt
import nl.tudelft.ipv8.messaging.serializeUInt

data class Ack(val hashCode: Int) : Serializable {
    override fun serialize(): ByteArray {
        return serializeUInt(hashCode.toUInt())
    }

    companion object Deserializer: Deserializable<Ack>{
        const val MESSAGE_ID = 14
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<Ack, Int> {
            return Ack(deserializeUInt(buffer,offset).toInt()) to buffer.size
        }

    }

}