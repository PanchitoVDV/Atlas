package be.esmay.atlas.common.network.packet;

import be.esmay.atlas.common.network.packet.packets.AtlasServerUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.MetadataUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerCommandPacket;
import be.esmay.atlas.common.network.packet.packets.ServerControlPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;

public interface PacketHandler {
    
    void handleHandshake(HandshakePacket packet);
    
    void handleAuthentication(AuthenticationPacket packet);
    
    void handleHeartbeat(HeartbeatPacket packet);
    
    void handleServerUpdate(ServerUpdatePacket packet);
    
    void handleServerList(ServerListPacket packet);
    
    void handleServerAdd(ServerAddPacket packet);
    
    void handleServerRemove(ServerRemovePacket packet);
    
    void handleServerInfoUpdate(ServerInfoUpdatePacket packet);
    
    void handleAtlasServerUpdate(AtlasServerUpdatePacket packet);
    
    void handleServerListRequest(ServerListRequestPacket packet);
    
    void handleServerCommand(ServerCommandPacket packet);
    
    void handleServerControl(ServerControlPacket packet);
    
    void handleMetadataUpdate(MetadataUpdatePacket packet);
    
}