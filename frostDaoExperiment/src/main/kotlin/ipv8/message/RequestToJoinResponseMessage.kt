package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable

data class RequestToJoinResponseMessage(
    override val id: Long,
    // if there are 10 members then my index will be 11
    val ok: Boolean,
    val  amountOfMembers: Int,
    // don't sendd ember mids, this won't scale.
//    val  memberMids: List<String>
) : FrostMessage{
    override fun serialize(): ByteArray {
        return "$id#$ok#$amountOfMembers".toByteArray()
    }

    companion object Deserializer: Deserializable<RequestToJoinResponseMessage>{
        const val MESSAGE_ID = 1
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<RequestToJoinResponseMessage, Int> {
            val (idstr,okstr, amountstr) = buffer.slice(offset until buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .split("#")
            return RequestToJoinResponseMessage(
                idstr.toLong(),
                okstr.toBooleanStrict(),
                amountstr.toInt(),
            ) to buffer.size
        }

    }

}
