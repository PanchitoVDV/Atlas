package be.esmay.atlas.common.network.packet.packets;

import be.esmay.atlas.common.network.packet.Packet;
import be.esmay.atlas.common.network.packet.PacketHandler;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class MetadataUpdatePacket implements Packet {
    
    private static final Gson GSON = new Gson();
    private static final Type METADATA_TYPE = new TypeToken<Map<String, String>>(){}.getType();
    
    private String serverId;
    private Map<String, String> metadata;
    
    @Override
    public int getId() {
        return 0x22;
    }
    
    @Override
    public void encode(ByteBuf buffer) {
        this.writeString(buffer, this.serverId);
        
        if (this.metadata == null) {
            this.writeString(buffer, null);
            return;
        }
        
        String json = GSON.toJson(this.metadata, METADATA_TYPE);
        this.writeString(buffer, json);
    }
    
    @Override
    public void decode(ByteBuf buffer) {
        try {
            this.serverId = this.readString(buffer);
            
            String json = this.readString(buffer);
            if (json == null) {
                this.metadata = null;
                return;
            }
            
            this.metadata = GSON.fromJson(json, METADATA_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode MetadataUpdatePacket from JSON", e);
        }
    }
    
    @Override
    public void handle(PacketHandler handler) {
        handler.handleMetadataUpdate(this);
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