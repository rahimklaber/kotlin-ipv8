package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable

data class ProposalStatusRequest(
    val id: Long
) : Serializable{
    override fun serialize(): ByteArray {
        return id.toString().encodeToByteArray()
    }
    companion object Deserializer: Deserializable<ProposalStatusRequest>{
        const val MESSAGE_ID = 12
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<ProposalStatusRequest, Int> {
            val id = buffer.slice(offset until buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .toLong()

            return ProposalStatusRequest(id) to buffer.size
        }

    }
}

data class ProposalStatusResponse(
    val id: Long,
    val isDone: Boolean
) : Serializable{
    override fun serialize(): ByteArray {
        return "$id#$isDone".toByteArray(Charsets.UTF_8)
    }


    companion object Deserializer: Deserializable<ProposalStatusResponse>{
        const val MESSAGE_ID = 13
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<ProposalStatusResponse, Int> {
            val (idstr, boolstr) = buffer.slice(offset until buffer.size)
                .toByteArray()
                .toString(Charsets.UTF_8)
                .split("#")

            return ProposalStatusResponse(idstr.toLong(),boolstr.toBooleanStrict()) to buffer.size
        }

    }

}
