package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import java.nio.Buffer

data class KeyGenCommitments(override val id: Long, val byteArray: ByteArray) : FrostMessage{
    override fun serialize(): ByteArray {
        return "$id#${byteArray.toHex()}".toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeyGenCommitments

        if (id != other.id) return false
        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + byteArray.contentHashCode()
        return result
    }

    companion object Deserializer: Deserializable<KeyGenCommitments>{
        const val MESSAGE_ID = 2
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<KeyGenCommitments, Int> {
            val(id, bytes) = buffer.sliceArray(offset until buffer.size)
                .toString(Charsets.UTF_8)
                .split("#")
            return KeyGenCommitments(
                id.toLong(),
                bytes.hexToBytes()
            ) to buffer.size
        }

    }
}

data class KeyGenShare(override val id: Long, val byteArray: ByteArray) : FrostMessage{
    override fun serialize(): ByteArray {
        return "$id#${byteArray.toHex()}".toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeyGenShare

        if (id != other.id) return false
        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + byteArray.contentHashCode()
        return result
    }

    companion object Deserializer: Deserializable<KeyGenShare>{
        const val MESSAGE_ID = 3

        override fun deserialize(buffer: ByteArray, offset: Int): Pair<KeyGenShare, Int> {
            val(id, bytes) = buffer.sliceArray(offset until buffer.size)
                .toString(Charsets.UTF_8)
                .split("#")
            return KeyGenShare(
                id.toLong(),
                bytes.hexToBytes()
            ) to buffer.size
        }

    }
}