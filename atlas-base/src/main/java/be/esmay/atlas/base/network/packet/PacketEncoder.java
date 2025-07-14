package be.esmay.atlas.base.network.packet;

import be.esmay.atlas.base.utils.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class PacketEncoder extends MessageToByteEncoder<Packet> {
    
    @Override
    protected void encode(ChannelHandlerContext ctx, Packet packet, ByteBuf out) throws Exception {
        int packetId = packet.getId();
        
        ByteBuf packetBuffer = ctx.alloc().buffer();
        try {
            packet.encode(packetBuffer);
            
            out.writeInt(packetId);
            out.writeInt(packetBuffer.readableBytes());
            out.writeBytes(packetBuffer);
        } finally {
            packetBuffer.release();
        }
        
        Logger.debug("Encoded packet {} with {} bytes", packet.getClass().getSimpleName(), out.readableBytes());
    }
    
}