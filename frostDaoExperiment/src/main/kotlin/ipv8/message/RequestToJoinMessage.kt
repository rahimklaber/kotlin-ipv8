package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import java.nio.charset.Charset

data class RequestToJoinMessage(
    override val id: Long, // some random id to keep track of the request,
    val orignalMid: String? = null, // for when forwarding requests to
) : FrostMessage {
    override fun serialize(): ByteArray {
        return "$id#${orignalMid ?: ""}".encodeToByteArray()
    }

    companion object Deserializer : Deserializable<RequestToJoinMessage> {
        const val MESSAGE_ID = 0
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<RequestToJoinMessage, Int> {
            val (idStr, originalMid) = buffer.slice(offset until buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .split("#")
            return RequestToJoinMessage(
                idStr.toLong(),
                originalMid.ifBlank { null }
            ) to buffer.size
        }

    }
}
