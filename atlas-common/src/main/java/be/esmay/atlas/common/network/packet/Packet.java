package be.esmay.atlas.common.network.packet;

import io.netty.buffer.ByteBuf;

public interface Packet {
    
    int getId();
    
    void encode(ByteBuf buffer);
    
    void decode(ByteBuf buffer);
    
    void handle(PacketHandler handler);
    
}