package be.esmay.atlas.common.network.packet.packets;

import be.esmay.atlas.common.models.ServerInfo;
import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ServerInfoUpdatePacket implements Packet {
    
    private static final Gson GSON = new Gson();
    
    private String serverId;
    private ServerInfo serverInfo;
    
    @Override
    public int getId() {
        return 0x20;
    }
    
    @Override
    public void encode(ByteBuf buffer) {
        this.writeString(buffer, this.serverId);
        
        if (this.serverInfo == null) {
            this.writeString(buffer, null);
            return;
        }
        
        String json = GSON.toJson(this.serverInfo);
        this.writeString(buffer, json);
    }
    
    @Override
    public void decode(ByteBuf buffer) {
        try {
            this.serverId = this.readString(buffer);
            
            String json = this.readString(buffer);
            if (json == null) {
                this.serverInfo = null;
                return;
            }
            
            this.serverInfo = GSON.fromJson(json, ServerInfo.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode ServerInfoUpdatePacket from JSON", e);
        }
    }
    
    @Override
    public void handle(PacketHandler handler) {
        handler.handleServerInfoUpdate(this);
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