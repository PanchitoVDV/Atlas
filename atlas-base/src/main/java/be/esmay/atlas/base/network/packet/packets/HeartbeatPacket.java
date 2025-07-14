package be.esmay.atlas.base.network.packet.packets;

import be.esmay.atlas.base.network.packet.Packet;
import be.esmay.atlas.base.network.packet.PacketHandler;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class HeartbeatPacket implements Packet {
    
    private String serverId;
    private long timestamp;
    private int onlinePlayers;
    private int maxPlayers;
    
    @Override
    public int getId() {
        return 0x03;
    }
    
    @Override
    public void encode(ByteBuf buffer) {
        this.writeString(buffer, this.serverId);
        buffer.writeLong(this.timestamp);
        buffer.writeInt(this.onlinePlayers);
        buffer.writeInt(this.maxPlayers);
    }
    
    @Override
    public void decode(ByteBuf buffer) {
        this.serverId = this.readString(buffer);
        this.timestamp = buffer.readLong();
        this.onlinePlayers = buffer.readInt();
        this.maxPlayers = buffer.readInt();
    }
    
    @Override
    public void handle(PacketHandler handler) {
        handler.handleHeartbeat(this);
    }
    
    private void writeString(ByteBuf buffer, String str) {
        if (str == null) {
            buffer.writeInt(-1);
            return;
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
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
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
}