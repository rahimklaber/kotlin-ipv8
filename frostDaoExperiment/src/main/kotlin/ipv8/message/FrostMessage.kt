package me.rahimklaber.frosttestapp.ipv8.message

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable

sealed interface FrostMessage: Serializable{
    val id: Long
}
fun messageIdFromMsg(msg: FrostMessage) : Int =
    when(msg){
        is KeyGenCommitments -> KeyGenCommitments.MESSAGE_ID
        is KeyGenShare -> KeyGenShare.MESSAGE_ID
        is RequestToJoinMessage -> RequestToJoinMessage.MESSAGE_ID
        is RequestToJoinResponseMessage -> RequestToJoinResponseMessage.MESSAGE_ID
        is Preprocess -> Preprocess.MESSAGE_ID
        is SignShare -> SignShare.MESSAGE_ID
        is SignRequest -> SignRequest.MESSAGE_ID
        is SignRequestResponse -> SignRequestResponse.MESSAGE_ID
        is StartKeyGenMsg -> StartKeyGenMsg.MESSAGE_ID
        else -> error("Compiler has bug?")
    }

fun deserializerFromId(id: Int) : Deserializable<out FrostMessage> =
    when(id){
         KeyGenCommitments.MESSAGE_ID -> KeyGenCommitments.Deserializer
         KeyGenShare.MESSAGE_ID -> KeyGenShare.Deserializer
         RequestToJoinMessage.MESSAGE_ID -> RequestToJoinMessage.Deserializer
         RequestToJoinResponseMessage.MESSAGE_ID -> RequestToJoinResponseMessage.Deserializer
         Preprocess.MESSAGE_ID -> Preprocess.Deserializer
         SignShare.MESSAGE_ID -> SignShare.Deserializer
         SignRequest.MESSAGE_ID -> SignRequest.Deserializer
         SignRequestResponse.MESSAGE_ID -> SignRequestResponse.Deserializer
        else -> error("could not find deserializer for id : $id")
    }
