package be.esmay.atlas.common.network.packet.packets;

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
public final class ServerCommandPacket implements Packet {
    
    private static final Gson GSON = new Gson();
    private String serverId;
    private String command;
    
    @Override
    public int getId() {
        return 0x30;
    }
    
    @Override
    public void encode(ByteBuf buffer) {
        ServerCommandData data = new ServerCommandData(this.serverId, this.command);
        String json = GSON.toJson(data);

        this.writeString(buffer, json);
    }
    
    @Override
    public void decode(ByteBuf buffer) {
        String json = this.readString(buffer);
        ServerCommandData data = GSON.fromJson(json, ServerCommandData.class);

        this.serverId = data.serverId;
        this.command = data.command;
    }
    
    @Override
    public void handle(PacketHandler handler) {
        handler.handleServerCommand(this);
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
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ServerCommandData {
        private String serverId;
        private String command;
    }
}