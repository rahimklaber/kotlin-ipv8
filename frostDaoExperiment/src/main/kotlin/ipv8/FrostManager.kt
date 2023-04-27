package me.rahimklaber.frosttestapp.ipv8

import FIleLogger
import SchnorrAgent
import SchnorrAgentMessage
import SchnorrAgentOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import me.rahimklaber.frosttestapp.ipv8.message.*
import mu.KotlinLogging
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.util.toHex
import java.util.*
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.reflect.KClass


fun FrostGroup.getIndex(mid: String) = members.find { it.peer == mid }?.index
fun FrostGroup.getMidForIndex(index: Int) = members.find { it.index == index }?.peer

abstract class NetworkManager{
    val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    abstract suspend fun send(peer: Peer, msg: FrostMessage): Boolean
    abstract fun getMyPeer() : Peer
    abstract fun getPeerFromMid(mid: String) : Peer
    abstract fun peers() : List<Peer>
    abstract suspend fun broadcast(msg: FrostMessage, recipients: List<Peer> = listOf()): Boolean
}
sealed interface Update{
    data class KeyGenDone(val pubkey: String) : Update
    data class StartedKeyGen(val id: Long) : Update
    data class ProposedKeyGen(val id: Long): Update
    data class SignRequestReceived(val id: Long, val fromMid: String ,val data: ByteArray) : Update
    data class SignDone(val id: Long, val signature: String) : Update
    data class TextUpdate(val text : String) : Update
    data class TimeOut(val id: Long) : Update
}


sealed interface FrostState{
    object NotReady : FrostState {
        override fun toString(): String = "NotReady"
    }

    data class RequestedToJoin(val id: Long) : FrostState {
        override fun toString(): String = "RequestedToJoin($id)"
    }

    object ReadyForKeyGen : FrostState {
        override fun toString(): String = "ReadyForKeyGen"
    }

    data class KeyGen(val id: Long) : FrostState {
        override fun toString(): String = "KeyGen($id)"
    }

    object ReadyForSign : FrostState {
        override fun toString(): String = "ReadyForSign"
    }

    data class ProposedSign(val id: Long) : FrostState {
        override fun toString(): String = "ProposedSign($id)"
    }

    data class Sign(val id: Long) : FrostState {
        override fun toString(): String = "Sign($id)"
    }

    data class ProposedJoin(val id: Long) : FrostState{
        override fun toString(): String = "ProposedJoin($id)"
    }
}

typealias OnJoinRequestResponseCallback = (Peer, RequestToJoinResponseMessage) -> Unit
typealias KeyGenCommitmentsCallaback = (Peer, KeyGenCommitments) -> Unit
typealias KeyGenShareCallback = (Peer, KeyGenShare) -> Unit
typealias SignShareCallback = (Peer, SignShare) -> Unit
typealias PreprocessCallback = (Peer, Preprocess) -> Unit
typealias SignRequestCallback = (Peer, SignRequest) -> Unit
typealias SignRequestResponseCallback = (Peer, SignRequestResponse) -> Unit

class FrostManager(
    val receiveChannel: Flow<Pair<Peer, FrostMessage>>,
//    val db: FrostDatabase,
    val networkManager: NetworkManager,
//    val getFrostInfo : () -> FrostGroup?,
    val updatesChannel: MutableSharedFlow<Update> = MutableSharedFlow(extraBufferCapacity = 10),
    var state: FrostState = FrostState.ReadyForKeyGen,
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    var frostInfo: FrostGroup? = null
//        get() = getFrostInfo()
    val msgProcessMap: MutableMap<KClass< out FrostMessage>, suspend (Peer,FrostMessage)->Unit> = mutableMapOf(

    )

    init {
        msgProcessMap[RequestToJoinMessage::class] = {peer, msg -> processRequestToJoin(peer,
            msg as RequestToJoinMessage
        )}
        msgProcessMap[RequestToJoinResponseMessage::class] = {peer, msg -> processRequestToJoinResponse(peer,
            msg as RequestToJoinResponseMessage
        )}
        msgProcessMap[KeyGenCommitments::class] = {peer, msg -> processKeyGenCommitments(peer, msg as KeyGenCommitments)}
        msgProcessMap[KeyGenShare::class] = {peer, msg -> processKeyGenShare(peer, msg as KeyGenShare)}
        msgProcessMap[SignShare::class] = {peer, msg -> processSignShare(peer, msg as SignShare)}
        msgProcessMap[Preprocess::class] = {peer, msg -> processPreprocess(peer, msg as Preprocess)}
        msgProcessMap[SignRequest::class] = {peer, msg -> processSignRequest(peer, msg as SignRequest)}
        msgProcessMap[SignRequestResponse::class] = {peer, msg -> processSignRequestResponse(peer,
            msg as SignRequestResponse
        )}
    }
    var keyGenJob: Job? = null
    var signJob: Job? = null

    val signJobs = mutableMapOf<Long, Job>()

    var agent : SchnorrAgent? = null
    var agentSendChannel = Channel<SchnorrAgentMessage>(1)
    var agentReceiveChannel = Channel<SchnorrAgentOutput>(1)

    var joinId = -1L;
    var joining = false;


    val onJoinRequestResponseCallbacks = mutableMapOf<Int,OnJoinRequestResponseCallback>()
    val onKeyGenCommitmentsCallBacks = mutableMapOf<Int,KeyGenCommitmentsCallaback>()
    val onKeyGenShareCallbacks = mutableMapOf<Int,KeyGenShareCallback>()
    val onPreprocessCallbacks = mutableMapOf<Int,PreprocessCallback>()
    val onSignShareCallbacks = mutableMapOf<Int,SignShareCallback>()
    val onSignRequestCallbacks = mutableMapOf<Int, SignRequestCallback>()
    val onSignRequestResponseCallbacks = mutableMapOf<Int, SignRequestResponseCallback>()
    private var cbCounter = 0;

    fun addJoinRequestResponseCallback(cb: OnJoinRequestResponseCallback) : Int{
        val id = cbCounter++
        onJoinRequestResponseCallbacks[id] = cb
        return id
    }
    fun addKeyGenCommitmentsCallbacks(cb : KeyGenCommitmentsCallaback) : Int{
        val id = cbCounter++
        onKeyGenCommitmentsCallBacks[id] = cb
        return id
    }

    fun removeKeyGenCommitmentsCallbacks(id: Int){
        onKeyGenCommitmentsCallBacks.remove(id)
    }
    fun addKeyGenShareCallback(cb: KeyGenShareCallback) : Int{
        val id = cbCounter++
        onKeyGenShareCallbacks[id] = cb
        return id
    }

    fun removeKeyGenShareCallback(id: Int){
        onKeyGenShareCallbacks.remove(id)
    }

    fun removeJoinRequestResponseCallback(id: Int){
        onJoinRequestResponseCallbacks.remove(id)
    }
    fun addSignShareCallback(cb: SignShareCallback) : Int{
        val id = cbCounter++
        onSignShareCallbacks[id] = cb
        return id
    }
    fun removeSignShareCallback(id: Int){
        onSignShareCallbacks.remove(id)
    }
    fun addPreprocessCallabac(cb: PreprocessCallback) : Int{
        val id = cbCounter++
        onPreprocessCallbacks[id] = cb
        return id
    }
    fun removePreprocessCallback(id: Int) {
        onPreprocessCallbacks.remove(id)
    }
    fun addOnSignRequestCallback(cb: SignRequestCallback) : Int{
        val id = cbCounter++
        onSignRequestCallbacks[id] = cb
        return id
    }
    fun addOnSignRequestResponseCallbac(cb: SignRequestResponseCallback) : Int{
        val id = cbCounter++
        onSignRequestResponseCallbacks[id] = cb
        return id
    }
    private val logger = KotlinLogging.logger {}
    init {
        scope.launch {
            receiveChannel
                .collect {
//                    Log.d("FROST", "received msg in frostmanager ${it.second}")
                    if (it.second is StartKeyGenMsg){
                        logger.info { "received cmd to start keygen" }
                       scope.launch(Dispatchers.IO) {
                           joinGroupBenchmark(it.first)
                       }
                        return@collect
                    }
                    processMsg(it)
                }
        }

        scope.launch(Dispatchers.Default) {
//            if (storedMe != null){
//                agent = SchnorrAgent(storedMe.frostKeyShare,storedMe.frostN,storedMe.frostIndex,storedMe.frostThresholod, agentSendChannel, agentReceiveChannel)
//                dbMe = storedMe
////                delay(5000)
//                frostInfo = FrostGroup(
//                    members = storedMe.frostMembers.map {
//                        val (mid, indexstr) = it.split("#").take(2)
//                        FrostMemberInfo(
//                           mid,
//                            indexstr.toInt()
//                        )
//                    },
//                    threshold = dbMe.frostThresholod,
//                    myIndex = dbMe.frostIndex
//                )
//                state = FrostState.ReadyForSign
//            }else{
//                dbMe = Me(
//                    -1,
//                    byteArrayOf(0),0,1,1, listOf("")
//                )
//            }
        }

    }

    suspend fun proposeSignAsync(data: ByteArray): Pair<Boolean,Long> {
        // we want to make multiple props at the same time?
        if(state !is FrostState.ReadyForSign){
            return false to 0
        }

        val signId = Random.nextLong()
//        state = FrostState.ProposedSign(signId)

         scope.launch {
            val ok = withTimeoutOrNull(60*1000/*10 minutes timeout todo make it configurable*/) {
                var responseCounter = 0
                val mutex = Mutex(true)
                val participatingIndices = mutableListOf<Int>()
                val callbacId = addOnSignRequestResponseCallbac { peer, signRequestResponse ->
                    if (signRequestResponse.id != signId){
                        return@addOnSignRequestResponseCallbac
                    }
                    if (signRequestResponse.ok)
                        responseCounter += 1
                    participatingIndices.add(
                        frostInfo?.getIndex(peer.mid)
                            ?: error(" FrostInfo is null. This is a bug. Maybe you are trying to sign without having first joined a group")
                    )
                    if (responseCounter >= frostInfo!!.threshold - 1) {
                        mutex.unlock()
                    }
                }
                val broadcastOk = networkManager.broadcast(SignRequest(signId, data))
                if (!broadcastOk){
                    updatesChannel.emit(Update.TimeOut(signId))
                    println("${networkManager.getMyPeer().mid}: failed to broadcast signrequest")
                    return@withTimeoutOrNull
                }
                mutex.lock()// make sure that enough peeps are available

                onSignRequestResponseCallbacks.remove(callbacId)


                val agentSendChannel = Channel<SchnorrAgentMessage>(1)
                val agentReceiveChannel = Channel<SchnorrAgentOutput>(1)

                signJobs[signId] = startSign(
                    signId, data,
                    agentSendChannel, agentReceiveChannel,
                    true,
                    (participatingIndices.plus(
                        frostInfo?.myIndex
                            ?: error(" FrostInfo is null. This is a bug. Maybe you are trying to sign without having first joined a group")
                    ))
                )
            }
             if(ok == null){
                 updatesChannel.emit(Update.TimeOut(1L))
                 println("${networkManager.getMyPeer().mid}: Timed out signing")
             }
        }


        return true to signId
    }

    suspend fun acceptProposedSign(id: Long, fromMid: String, data: ByteArray){
        val receivedAc = networkManager.send(networkManager.getPeerFromMid(fromMid),SignRequestResponse(id,true))
        if (!receivedAc){
            updatesChannel.emit(Update.TimeOut(id))
            return
        }
        val agentSendChannel = Channel<SchnorrAgentMessage>(1)
        val agentReceiveChannel = Channel<SchnorrAgentOutput>(1)

        //todo wait for a msg here?

        signJobs[id] = startSign(
            id,
            data,
            agentSendChannel, agentReceiveChannel
        )
    }


//    suspend fun proposeSign(data: ByteArray){
//        when(state){
//            FrostState.ReadyForSign -> Unit
//            else -> {
//                updatesChannel.emit(Update.TextUpdate("not ready for sign"))
//                return
//            }
//        }
//        val signId = Random.nextLong()
//        state = FrostState.ProposedSign(signId)
//
//
//        var responseCounter = 0
//        val mutex = Mutex(true)
//        val participatingIndices = mutableListOf<Int>()
//        val callbacId = addOnSignRequestResponseCallbac { peer, signRequestResponse ->
//            if (signRequestResponse.ok)
//                responseCounter += 1
//            participatingIndices.add(
//                frostInfo?.getIndex(peer.mid)
//                    ?: error(" FrostInfo is null. This is a bug. Maybe you are trying to sign without having first joined a group")
//            )
//            if (responseCounter >= frostInfo!!.threshold - 1) {
//                mutex.unlock()
//            }
//        }
//        networkManager.broadcast(SignRequest(signId, data))
//        mutex.lock()// make sure that enough peeps are available
//
//        onSignRequestResponseCallbacks.remove(callbacId)
//
//        Log.d("FROST", "started sign")
//         startSign(
//            signId, data, true,
//            (participatingIndices.plus(
//                frostInfo?.myIndex
//                    ?: error(" FrostInfo is null. This is a bug. Maybe you are trying to sign without having first joined a group")
//            ))
//        )
//
//    }

    private suspend fun startSign(
        signId: Long,
        data: ByteArray,
        agentSendChannel : Channel<SchnorrAgentMessage> ,
        agentReceiveChannel :  Channel<SchnorrAgentOutput>,
        isProposer: Boolean = false,
        participantIndices: List<Int> = listOf(),
    ) = scope.launch {
        state = FrostState.Sign(signId)

        val mutex = Mutex(true)

        // whether we received a preprocess msg from the initiator
        // this signals the other peers to start
        val participantIndices = participantIndices.toMutableList()

        val signShareCbId = addSignShareCallback { peer, msg ->
            if (msg.id  != signId)
                return@addSignShareCallback
            launch {
//                delay(200)
                agentSendChannel.send(
                    SchnorrAgentOutput.SignShare(
                        msg.bytes,
                        frostInfo?.getIndex(peer.mid)!!
                    )
                )
            }
        }
        val preprocessCbId = addPreprocessCallabac { peer, preprocess ->
            if(preprocess.id != signId){
                return@addPreprocessCallabac
            }
            launch {
//                delay(200)
                if (!isProposer && mutex.isLocked && preprocess.participants.isNotEmpty()) { // only the init message has size > 0
                    mutex.unlock()
                    participantIndices.addAll(preprocess.participants)
                }
                 agentSendChannel.send(
                    SchnorrAgentOutput.SignPreprocess(
                        preprocess.bytes,
                        frostInfo?.getIndex(peer.mid)!!,
                    )
                )
            }
        }

        fun fail(){
            state =
                FrostState.ReadyForSign
            //todo timed out/ messaged dropped same thing?
            removePreprocessCallback(preprocessCbId)
            removeSignShareCallback(signShareCbId)
            scope.launch {
                updatesChannel.emit(Update.TimeOut(signId))
            }
            cancel()
        }

        val (sendSemaphore, semaphoreMaxPermits) = if(isProposer){
            Semaphore(participantIndices.size,) to participantIndices.size
        }else{
            //at this point we don't now the amount of participants, so use amount of peers
            Semaphore(networkManager.peers().size) to networkManager.peers().size
        }
        launch {
            for (output in agentReceiveChannel){
                when (output) {
                    is SchnorrAgentOutput.SignPreprocess -> launch {
                        sendSemaphore.acquire()
                        val ok = sendToParticipants(participantIndices,
                            Preprocess(
                                signId,
                                output.preprocess,
                                if (isProposer) {
                                    participantIndices
                                } else {
                                    listOf()
                                }
                            )
                        )
                        sendSemaphore.release()
                        if(!ok)
                            fail()
                    }
                    is SchnorrAgentOutput.SignShare -> {
                        sendSemaphore.acquire()
                        val ok = sendToParticipants(participantIndices, SignShare(signId, output.share))
                        sendSemaphore.release()
                        if(!ok)
                            fail()
                    }
                    is SchnorrAgentOutput.Signature -> updatesChannel.emit(Update.SignDone(signId,output.signature.toHex()))
                    else -> {}
                }
            }
        }
        if(!isProposer){
            // wait for initial msg to signal that we can start
            val lockReceived = withTimeoutOrNull(WAIT_FOR_INITIAL_PREPROCESS_MILLIS){
                mutex.lock()
            }

            if (lockReceived == null){
                fail()
            }
        }

        val signDone =withTimeoutOrNull(SIGN_TIMEOUT_MILLIS){
            agent!!.startSigningSession(signId.toInt(),data, byteArrayOf(), agentSendChannel,agentReceiveChannel)
        }
        if (signDone == null){
            fail()
        }
        while (sendSemaphore.availablePermits != semaphoreMaxPermits){
            delay(1000)
        }
        state = FrostState.ReadyForSign
        // wait for a second to allow for buffers to clear
        delay(1000)
        cancel()

    }

    private suspend fun startKeyGen(id: Long, midsOfNewGroup: List<String>, isNew: Boolean = false) = scope.launch{
        fun cancelKeyGenJob(){
            cancel()
        }
        joinId = id

        agentSendChannel = Channel(10)
        agentReceiveChannel = Channel(10)

        val amount = midsOfNewGroup.size
        val midsOfNewGroup = midsOfNewGroup
            .sorted()
        val getIndex = { mid: String ->
            midsOfNewGroup.indexOf(mid) + 1
        }
        val getMidFromIndex = { index: Int ->
            midsOfNewGroup[index-1]
        }

        val index = getIndex(networkManager.getMyPeer().mid)

        agent = SchnorrAgent(amount,index,midsOfNewGroup.size, agentSendChannel, agentReceiveChannel)

        val mutex = Mutex(true)
        val mutexForCount = Mutex()
        var commitmentsReceived = 0
        var sharesReceived = 0

        var commitmentsSent = 0
        var sharesSent = 0

        val commitmentCbId = addKeyGenCommitmentsCallbacks{ peer, msg ->
            launch {
                mutexForCount.withLock {
                    commitmentsReceived+=1
                }
                agentSendChannel.send(SchnorrAgentMessage.KeyCommitment(msg.byteArray,getIndex(peer.mid)))
//                delay(200)
                // I gues it could receive two msg almost at the same time and both of them check that the mutex is locked
                // before unlocking?
                kotlin.runCatching {
                    if (!isNew && mutex.isLocked)
                        mutex.unlock()
                }.getOrElse {
                    launch { FIleLogger("${networkManager.getMyPeer().mid}:  exception when dealing with mutex: $it: msg: $msg") }
                }
            }
        }
        val shareCbId = addKeyGenShareCallback { peer, keyGenShare ->
            launch {
                mutexForCount.withLock {
                    sharesReceived+=1
                }
                delay(200)
                agentSendChannel.send(SchnorrAgentMessage.DkgShare(keyGenShare.byteArray,getIndex(peer.mid)))
            }
        }
        fun fail(){
           launch {
               FIleLogger("${networkManager.getMyPeer().mid}:  fail was called.")
               delay(100)
           }
            state = if (isNew){
                FrostState.ReadyForKeyGen
            }else{
                FrostState.ReadyForSign
            }
            removeKeyGenCommitmentsCallbacks(commitmentCbId)
            removeKeyGenShareCallback(shareCbId)
            //todo timed out/ messaged dropped same thing?
            scope.launch {
                updatesChannel.emit(Update.TimeOut(id))
            }
            cancel()
        }
        val semaphoreMaxPermits = midsOfNewGroup.size
        val sendSemaphore = Semaphore(semaphoreMaxPermits,)

        launch {
            for (agentOutput in agentReceiveChannel) {
//                Log.d("FROST", "sending $agentOutput")
                when(agentOutput){
                    is SchnorrAgentOutput.DkgShare -> {

                       scope.launch {
                            sendSemaphore.acquire()
                           val ok = networkManager.send(
                               networkManager.getPeerFromMid(getMidFromIndex(agentOutput.forIndex)),
                               KeyGenShare(joinId, agentOutput.share)
                           )
                        sendSemaphore.release()
                           if(!ok){
                               println("send failed when sending dkg share")
                               FIleLogger("${networkManager.getMyPeer().mid}:  send failed when sending dkg share")
                               fail()
                           }
                           mutexForCount.withLock {
                               sharesSent +=1
                           }

                       }
                    }
                    is SchnorrAgentOutput.KeyCommitment -> {

                       scope.launch {
                        sendSemaphore.acquire()
                           val commitment = KeyGenCommitments(joinId, agentOutput.commitment)
                           val ok = networkManager.broadcast(commitment,
                               (midsOfNewGroup  - networkManager.getMyPeer().mid)
                                   .map {
                                       networkManager.getPeerFromMid(it)
                                   }
                           )
                            sendSemaphore.release()
//                           FIleLogger("sending $commitment, size: ${commitment.serialize().size} bytes")
                        if(!ok) {
                            println("broadcast failed when sending keycommitment")
                            FIleLogger("${networkManager.getMyPeer().mid}: broadcast failed when sending keycommitment")
                            fail()
                        }
                           mutexForCount.withLock {
                               commitmentsSent +=1
                           }
                       }
                    }
                    is SchnorrAgentOutput.KeyGenDone -> {
                        updatesChannel.emit(Update.KeyGenDone(agentOutput.pubkey.toHex()))
                        while (sendSemaphore.availablePermits != semaphoreMaxPermits){
//                            println("looping")
                            FIleLogger("${networkManager.getMyPeer().mid}:  Looping; waiting for ${semaphoreMaxPermits - sendSemaphore.availablePermits}")
                            delay(1000)
                        }
                        this@FrostManager.frostInfo = FrostGroup(
                            (midsOfNewGroup.filter { it != networkManager.getMyPeer().mid }).map {
                                FrostMemberInfo(
                                    it,
                                    getIndex(it)
                                )
                            },
                            index,
                            threshold = midsOfNewGroup.size
                        )


                        state = FrostState.ReadyForSign
                        //cancel when done
//                        println("cancelling Keygen process in manager")
                        cancelKeyGenJob()
                    }
                    else -> {
                        error("RCEIVED OUTPUT FOR SIGNING WHILE DOING KEYGEN. SHOULD NOT HAPPEN")
                    }
                }
            }
        }
        if(!isNew){
            val receivedSignal = withTimeoutOrNull(WAIT_FOR_KEYGEN_TIMEOUT_MILLIS){
                mutex.lock()
            }
            // we did not receive signal to start before timeout
            if (receivedSignal == null){
//                state = FrostState.ReadyForKeyGen
//                scope.launch {
//                    updatesChannel.emit(Update.TimeOut(id))
//                }
//                cancel()
                FIleLogger("${networkManager.getMyPeer().mid} : didn't receive signal to start")
                fail()
            }
        }
//        launch {
//            while(true){
//                delay(10000)
//                println("${networkManager.getMyPeer().mid}: frostgroup size: ${frostInfo?.amount ?: "no group"} commitments receved: $commitmentsReceived, shares received: $sharesReceived, commitments sents: $commitmentsSent, shares sent: $sharesSent")
//                delay(70000)
//            }
//        }
        val keygenDone = withTimeoutOrNull(KEYGEN_TIMEOUT_MILLIS){
            agent!!.startKeygen()
        }

        //timeout without being done
        if(keygenDone == null){
//            scope.launch {
//                updatesChannel.emit(Update.TimeOut(id))
//            }
//            state = if(isNew){
//                FrostState.ReadyForKeyGen
//            }else{
//                FrostState.ReadyForSign
//            }
//            cancel()
            FIleLogger("${networkManager} : timeout. Keygen didn't finish in time" )
            fail()
        }

//    delay(1000)

    }

    // for running on pc
    suspend fun joinGroupBenchmark(peer: Peer){
        joinId = Random.nextLong()
        joining = true
        if (!(state == FrostState.NotReady || state == FrostState.ReadyForKeyGen)) {
            return
        }
        state = FrostState.ProposedJoin(joinId)
        updatesChannel.emit(Update.ProposedKeyGen(joinId))

        val ok = scope.async(Dispatchers.Default) {
            // delay to start waiting before sending msg
            delay(1000)
            networkManager.send(peer,RequestToJoinMessage(joinId))
        }
        val peersInGroup = withTimeoutOrNull(WAIT_FOR_KEYGEN_TIMEOUT_MILLIS ){
            waitForJoinResponse(joinId)
        }

        // in this case, we have not received enough confirmations of peeers before timing out.
        // or messages were dropped
        if (peersInGroup == null || !ok.await()){
            scope.launch {
                updatesChannel.emit(Update.TimeOut(joinId))
            }
            state = FrostState.ReadyForKeyGen
            return
        }
        state = FrostState.KeyGen(joinId)
        keyGenJob =  startKeyGen(joinId,(peersInGroup + networkManager.getMyPeer()).map { it.mid },true)
    }
//    suspend fun joinGroup(){
//        joinId = Random.nextLong()
//        joining = true
//        if (!(state == FrostState.NotReady || state == FrostState.ReadyForKeyGen)) {
//            return
//        }
//        state = FrostState.ProposedJoin(joinId)
//        updatesChannel.emit(Update.ProposedKeyGen(joinId))
//
//        val ok = scope.async(Dispatchers.Default) {
//            // delay to start waiting before sending msg
//            delay(1000)
//            networkManager.broadcast(RequestToJoinMessage(joinId))
//        }
//        val peersInGroup = withTimeoutOrNull(WAIT_FOR_KEYGEN_TIMEOUT_MILLIS ){
//            waitForJoinResponse(joinId)
//        }
//
//        // in this case, we have not received enough confirmations of peeers before timing out.
//        // or messages were dropped
//        if (peersInGroup == null || !ok.await()){
//            scope.launch {
//                updatesChannel.emit(Update.TimeOut(joinId))
//            }
//            state = FrostState.ReadyForKeyGen
//            return
//        }
//        state = FrostState.KeyGen(joinId)
//        keyGenJob =  startKeyGen(joinId,(peersInGroup + networkManager.getMyPeer()).map { it.mid },true)
//    }

    private suspend fun waitForJoinResponse(id: Long): List<Peer> {
        var counter = 0
        val mutex = Mutex(true)
        val peers = mutableListOf<Peer>()
        var amount: Int? = null
        val cbId = addJoinRequestResponseCallback{ peer, msg ->
            if (msg.id ==id){
                if (amount == null){
                    amount = msg.amountOfMembers
                }
                peers.add(peer)
                counter++
                if (counter == amount)
                    mutex.unlock()
            }
        }
        mutex.lock()
        removeJoinRequestResponseCallback(cbId)
        return peers
    }

    suspend fun processMsg(pair: Pair<Peer, FrostMessage>) {
        val (peer, msg) = pair
        msgProcessMap[msg::class]?.let { it(peer, msg) }

    }

    private suspend fun processRequestToJoin(peer: Peer, msg: RequestToJoinMessage) {
        when(state){
            FrostState.ReadyForKeyGen, FrostState.ReadyForSign -> {
                state = FrostState.KeyGen(msg.id)
                scope.launch {
                    updatesChannel.emit(Update.StartedKeyGen(msg.id))
                    // if this is the message from the "orignator"
                    if(msg.orignalMid == null)
                    {
                        if(frostInfo != null){
                            val ok = sendToParticipants(frostInfo!!.members.map { it.index },RequestToJoinMessage(msg.id, orignalMid = peer.mid))
                            if (!ok){
                                logger.info { "Sending request to join to frost members failed" }
                                return@launch
                            }

                        }
                        networkManager.send(peer,RequestToJoinResponseMessage(msg.id, true, frostInfo?.amount ?: 1,
                            /*(frostInfo?.members?.map { it.peer }
                                ?.plus(networkManager.getMyPeer().mid))
                                ?: listOf(
                                    networkManager.getMyPeer().mid
                                )*/))
                        keyGenJob = startKeyGen(msg.id,
                            frostInfo?.members?.map(FrostMemberInfo::peer)?.plus(peer.mid)?.plus(networkManager.getMyPeer().mid)
                                ?: (listOf(networkManager.getMyPeer()) + peer).map { it.mid }
                        )
                    }else{
                        // in this, we know that we arre in a frost group
                        networkManager.send(networkManager.getPeerFromMid(msg.orignalMid),RequestToJoinResponseMessage(msg.id, true, frostInfo?.amount ?: 1,
                            /*(frostInfo?.members?.map { it.peer }
                                ?.plus(networkManager.getMyPeer().mid))
                                ?: listOf(
                                    networkManager.getMyPeer().mid
                                )*/))
                        keyGenJob = startKeyGen(msg.id,
                            frostInfo?.members?.map(FrostMemberInfo::peer)?.plus(msg.orignalMid)?.plus(networkManager.getMyPeer().mid)
                                ?: error("we are not in a frostGroup, yet some thinks that we are")
                        )
                    }


                }
            }
            else -> {
                // log cannot do this while in this state?
                // Maybe I should send a message bac to indicate this?
                // Actually I probably should
                networkManager.send(peer, RequestToJoinResponseMessage(msg.id,false,0))
            }
        }
    }

    private suspend fun sendToParticipants(participantIndices: List<Int>, frostMessage: FrostMessage): Boolean {
        val participantPeers = (participantIndices - (frostInfo?.myIndex ?: error("frostinfo is null. this is a bug.")))
            .map {
                networkManager.getPeerFromMid(frostInfo?.getMidForIndex(it) ?: error("frostinfo null, this is a bug"))
            }
        return networkManager.broadcast(frostMessage,participantPeers)
    }

    private fun processRequestToJoinResponse(peer: Peer, msg: RequestToJoinResponseMessage) {
        onJoinRequestResponseCallbacks.forEach {
            it.value(peer,msg)
        }
//        when(state){
//            is FrostState.RequestedToJoin ->{
//                if (msg.id != (state as FrostState.RequestedToJoin).id){
//                    //todo deal with this
//                    // send back a msg?
//                    return
//                }
//                state =
//            }
//        }
    }
    private fun processKeyGenCommitments(peer: Peer, msg: KeyGenCommitments){
        onKeyGenCommitmentsCallBacks.forEach {
            it.value(peer,msg)
        }
    }
    private fun processKeyGenShare(peer: Peer, msg: KeyGenShare){
        onKeyGenShareCallbacks.forEach {
            it.value(peer,msg)
        }
    }

    private fun processSignShare(peer: Peer, msg: SignShare){
        onSignShareCallbacks.forEach{
            it.value(peer,msg)
        }
    }
    private fun processPreprocess(peer: Peer, msg: Preprocess){
        onPreprocessCallbacks.forEach {
            it.value(peer,msg)
        }
    }
    private fun processSignRequest(peer: Peer, msg: SignRequest){
        onSignRequestCallbacks.forEach {
            it.value(peer,msg)
        }
        when(state){
            FrostState.ReadyForSign -> {
                scope.launch {
                    updatesChannel.emit(Update.SignRequestReceived(msg.id,peer.mid,msg.data))
                    //To make it easier when benchmarking
                    acceptProposedSign(msg.id,peer.mid,msg.data)
//                    updatesChannel.emit(Update.TextUpdate("startet sign"))
//                    networkManager.send(peer,SignRequestResponse(msg.id,true))
//                    startSign(msg.id,msg.data)
                }
            }
                else -> {}
        }
    }
    private fun processSignRequestResponse(peer: Peer, msg: SignRequestResponse){
        onSignRequestResponseCallbacks.forEach {
            it.value(peer,msg)
        }
    }

    companion object{
        const val SIGN_TIMEOUT_MILLIS = 2  *60 * 1000L
        const val KEYGEN_TIMEOUT_MILLIS = 2  *60 * 1000L
        const val WAIT_FOR_KEYGEN_TIMEOUT_MILLIS = 2 * 60 * 1000L
        const val WAIT_FOR_INITIAL_PREPROCESS_MILLIS = 2 * 60 * 1000L
        // const val timeout ...
    }

}
