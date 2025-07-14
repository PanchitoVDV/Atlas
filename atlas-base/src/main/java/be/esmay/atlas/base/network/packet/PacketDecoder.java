package be.esmay.atlas.base.network.packet;

import be.esmay.atlas.base.utils.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public final class PacketDecoder extends ByteToMessageDecoder {
    
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 8) {
            return;
        }
        
        in.markReaderIndex();
        
        int packetId = in.readInt();
        int payloadLength = in.readInt();
        
        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }
        
        Packet packet = PacketRegistry.createPacket(packetId);
        if (packet == null) {
            Logger.warn("Unknown packet ID: {}", packetId);
            in.skipBytes(payloadLength);
            return;
        }
        
        ByteBuf payloadBuffer = in.readSlice(payloadLength);
        packet.decode(payloadBuffer);
        
        out.add(packet);
        
        Logger.debug("Decoded packet {} with {} bytes", packet.getClass().getSimpleName(), payloadLength);
    }
    
}