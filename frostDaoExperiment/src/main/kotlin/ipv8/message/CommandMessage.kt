package me.rahimklaber.frosttestapp.ipv8.message

import me.rahimklaber.frosttestapp.ipv8.Update
import me.rahimklaber.frosttestapp.ipv8.message.FrostMessage
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable

class StartKeyGenMsg: FrostMessage {
    override val id: Long
        get() = 0 // not needed
    override fun serialize(): ByteArray {
        return byteArrayOf()
    }

    companion object Deserializer: Deserializable<StartKeyGenMsg> {
        const val MESSAGE_ID = 20
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<StartKeyGenMsg, Int> {
            return StartKeyGenMsg() to buffer.size
        }
    }
}
