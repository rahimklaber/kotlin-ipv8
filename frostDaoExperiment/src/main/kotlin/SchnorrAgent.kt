import generated.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface SchnorrAgentMessage{
    data class KeyCommitment(val commitment: ByteArray, val fromIndex: Int) : SchnorrAgentMessage
    data class DkgShare(val share: ByteArray, val fromIndex: Int) : SchnorrAgentMessage

}

sealed interface SchnorrAgentOutput{

    data class KeyCommitment(val commitment: ByteArray, val fromIndex: Int) : SchnorrAgentOutput

    data class DkgShare(val share: ByteArray, val fromIndex: Int, val forIndex: Int) :
        SchnorrAgentOutput

    data class KeyGenDone(val index: Int, val pubkey: ByteArray): SchnorrAgentOutput

    data class SignPreprocess(val preprocess: ByteArray, val fromIndex: Int) : SchnorrAgentOutput,
        SchnorrAgentMessage

    data class SignShare(val share:ByteArray, val fromIndex: Int): SchnorrAgentOutput,
        SchnorrAgentMessage

    data class Signature(val signature : ByteArray) : SchnorrAgentOutput
}

class SchnorrAgent(
    private val numberOfParticipants: Int,
    val index: Int,
    private val threshold: Int,
    private val receiveChannel: ReceiveChannel<SchnorrAgentMessage>,
    private val outputChannel: SendChannel<SchnorrAgentOutput>,
){

    constructor(
        keyByteArray: ByteArray, numberOfParticipants: Int,
        index: Int,
        threshold: Int,
        receiveChannel: ReceiveChannel<SchnorrAgentMessage>,
        outputChannel: SendChannel<SchnorrAgentOutput>
    ) : this(numberOfParticipants, index, threshold, receiveChannel, outputChannel){
        keyWrapper = SchnorrKeyWrapper.from_serialized(keyByteArray)
    }

    lateinit var keyWrapper: SchnorrKeyWrapper
    suspend fun startKeygen() = coroutineScope {
        var key_gen_machine =
            SchnorrKeyGenWrapper(threshold, numberOfParticipants, index, "test")
        // create commitment
        val res_key_1 = SchnorrKeyGenWrapper.key_gen_1_create_commitments(key_gen_machine)
        val commitment = res_key_1._res
        key_gen_machine = ResultKeygen1.get_keygen(res_key_1)

        outputChannel.send(SchnorrAgentOutput.KeyCommitment(commitment, index))
        val param_keygen_2_msgs = mutableListOf<SchnorrAgentMessage.KeyCommitment>()
        val param_keygen_3_msgs = mutableListOf<SchnorrAgentMessage.DkgShare>()
        val notification1Mutex = Mutex(true)
        val notification2Mutex = Mutex(true)
        fun clearMsgs(){
            param_keygen_2_msgs.clear()
            param_keygen_3_msgs.clear()
        }

        val msgHandlerJob = launch {
            var keycommitmentReceived = 0
            var dkgSharesReceived = 0
            for (msg in receiveChannel){
                when(msg){
                    is SchnorrAgentMessage.DkgShare -> {
                        param_keygen_3_msgs.add(msg)
//                        Log.d("FROST", "enough dkg")
                        dkgSharesReceived++
                        println(msg.fromIndex)
                        if (dkgSharesReceived == numberOfParticipants - 1)
                            notification2Mutex.unlock()
                    }
                    is SchnorrAgentMessage.KeyCommitment -> {
//                        Log.d("FROST","enought commitments")
                        param_keygen_2_msgs.add(msg)

                        keycommitmentReceived++
                        if (keycommitmentReceived == numberOfParticipants - 1)
                            notification1Mutex.unlock()
                    }

                    else -> throw IllegalArgumentException("Message $msg cannot be handled while in keygen phase")
                }
            }
        }

        //use mutex as a semaphore?? basically to signal that we have enough commitments
        notification1Mutex.lock()
        val param_keygen_2 = ParamsKeygen2()

        param_keygen_2_msgs.forEach {
            val (bytes,from) = it
            param_keygen_2.add_commitment_from_user(from,bytes)
        }

        // create key share for others ????
        val res_key_2 = SchnorrKeyGenWrapper.key_gen_2_generate_shares(key_gen_machine,param_keygen_2)
        // have to do this first, since getting the key gen machine consumes `self`
        res_key_2._user_indices.forEachIndexed{ arrayIdx, userIndex ->
            outputChannel.send(
                SchnorrAgentOutput.DkgShare(
                    res_key_2.get_shares_at(arrayIdx.toLong()),
                    index,
                    userIndex
                )
            )
        }

        key_gen_machine = ResultKeygen2.get_keygen(res_key_2)
        notification2Mutex.lock()

        val params_keygen_3 = ParamsKeygen3()

        param_keygen_3_msgs.forEach {
            val (bytes,from) = it

            params_keygen_3.add_share_from_user(from,bytes)
        }

        keyWrapper = SchnorrKeyGenWrapper.key_gen_3_complete(key_gen_machine,params_keygen_3)
        msgHandlerJob.cancel()

        outputChannel.send(SchnorrAgentOutput.KeyGenDone(index, keyWrapper._bitcoin_encoded_key))
    }

    suspend fun startSigningSession(sessionId: Int, msg: ByteArray, prevoutScript: ByteArray,
                                    receiveChannel: ReceiveChannel<SchnorrAgentMessage>,
                                    outputChannel: SendChannel<SchnorrAgentOutput>
                                    ) = coroutineScope{
        val param_sign_2_msgs = mutableListOf<SchnorrAgentOutput.SignPreprocess>()
        val param_sign_3_msgs = mutableListOf<SchnorrAgentOutput.SignShare>()
        val mutex = Mutex(true)

        val msgHandlerJob = launch {
            var preprocessReceived = 0
            var signSharesReceived = 0
            for (msg in receiveChannel){
                when(msg){
                    is SchnorrAgentOutput.SignPreprocess -> {

                        param_sign_2_msgs.add(msg)

                        preprocessReceived++
                        if (preprocessReceived == threshold - 1)
                            mutex.unlock()
                    }

                    is SchnorrAgentOutput.SignShare -> {
                        param_sign_3_msgs.add(msg)

                        signSharesReceived++
                        if (signSharesReceived == threshold-1)
                            mutex.unlock()
                    }
                    else -> throw IllegalArgumentException("cannot handle Message $msg when signing.")
                }
            }
        }

        var signWrapper = SchnorrSignWrapper.new_instance_for_signing(keyWrapper,threshold.toLong())

        // preprocess step
        val res_sign_1 = SchnorrSignWrapper.sign_1_preprocess(signWrapper)
        val preprocess_commitment = res_sign_1._preprocess
        signWrapper = SignResult1.get_wrapper(res_sign_1)

        outputChannel.send(SchnorrAgentOutput.SignPreprocess(preprocess_commitment, index))

        mutex.lock()
        val params_sign_2 = SignParams2()
        param_sign_2_msgs.forEach {
            val (bytes, from) = it
            params_sign_2.add_commitment_from_user(from,bytes)
        }

        val res_sign_2 = SchnorrSignWrapper.sign_2_sign_normal(signWrapper,params_sign_2, msg)
//        val res_sign_2 = SchnorrSignWrapper.sign_2_sign(signWrapper,params_sign_2, msg,prevoutScript)

        val share = res_sign_2._share
        signWrapper = SignResult2.get_wrapper(res_sign_2)

        outputChannel.send(SchnorrAgentOutput.SignShare(share, index))

/*        if (index != 1){
            msgHandlerJob.cancel()
            return@coroutineScope
        }*/


        mutex.lock()

        val param_sign_3 = SignParams3()
        param_sign_3_msgs.forEach {
            val (bytes,from) = it
            param_sign_3.add_share_of_user(from,bytes)
        }

        val res_sign_3 = SchnorrSignWrapper.sign_3_complete(signWrapper,param_sign_3)

        msgHandlerJob.cancel()
        outputChannel.send(SchnorrAgentOutput.Signature(res_sign_3))
    }
}
