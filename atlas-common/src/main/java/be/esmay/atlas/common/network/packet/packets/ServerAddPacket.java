package be.esmay.atlas.common.network.packet.packets;

import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import be.esmay.atlas.common.models.ServerInfo;
import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ServerAddPacket implements Packet {
    
    private static final Gson GSON = new Gson();
    private ServerInfo serverInfo;
    
    @Override
    public int getId() {
        return 0x12;
    }
    
    @Override
    public void encode(ByteBuf buffer) {
        String json = GSON.toJson(this.serverInfo);
        this.writeString(buffer, json);
    }
    
    @Override
    public void decode(ByteBuf buffer) {
        String json = this.readString(buffer);
        this.serverInfo = GSON.fromJson(json, ServerInfo.class);
    }
    
    @Override
    public void handle(PacketHandler handler) {
        handler.handleServerAdd(this);
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