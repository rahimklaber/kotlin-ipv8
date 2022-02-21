package nl.tudelft.ipv8.jvm.swap;

import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.peerdiscovery.DiscoveryCommunity

class SwapCommunity : Community(){
    override val serviceId: String = "a4574ac4f62cb96ff2f196d996e40be2e04d2df6"

    @OptIn(ExperimentalUnsignedTypes::class)
    fun broadcastMsg(msg: TradeMessage){
        val packet = serializePacket(TradeMessage.MESSAGE_ID,msg)
        for(peer in getPeers()){
            ipv8Stuff.swapCommunity.send(peer.address,packet)
        }
    }

    class Factory : Overlay.Factory<SwapCommunity>(SwapCommunity::class.java)

}
