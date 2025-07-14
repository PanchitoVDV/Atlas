package be.esmay.atlas.common.network.packet.packets;

import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ServerListRequestPacket implements Packet {
    
    private String requesterId;
    
    @Override
    public int getId() {
        return 0x14;
    }
    
    @Override
    public void encode(ByteBuf buffer) {
        this.writeString(buffer, this.requesterId);
    }
    
    @Override
    public void decode(ByteBuf buffer) {
        this.requesterId = this.readString(buffer);
    }
    
    @Override
    public void handle(PacketHandler handler) {
        handler.handleServerListRequest(this);
    }
    
    private void writeString(ByteBuf buffer, String str) {
        if (str == null) {
            buffer.writeInt(-1);
            return;
        }
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.writeInt(bytes.length);
        buffer.writeBytes(bytes);
    }
    
    private String readString(ByteBuf buffer) {
        int length = buffer.readInt();
        if (length == -1) {
            return null;
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
    
}