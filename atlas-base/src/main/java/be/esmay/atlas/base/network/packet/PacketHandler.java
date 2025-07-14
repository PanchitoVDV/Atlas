package be.esmay.atlas.base.network.packet;

import be.esmay.atlas.base.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.base.network.packet.packets.HandshakePacket;
import be.esmay.atlas.base.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.base.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.base.network.packet.packets.ServerListPacket;
import be.esmay.atlas.base.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.base.network.packet.packets.ServerUpdatePacket;

public interface PacketHandler {
    
    void handleHandshake(HandshakePacket packet);
    
    void handleAuthentication(AuthenticationPacket packet);
    
    void handleHeartbeat(HeartbeatPacket packet);
    
    void handleServerUpdate(ServerUpdatePacket packet);
    
    void handleServerList(ServerListPacket packet);
    
    void handleServerAdd(ServerAddPacket packet);
    
    void handleServerRemove(ServerRemovePacket packet);
    
}