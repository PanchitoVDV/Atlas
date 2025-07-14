package be.esmay.atlas.common.network.packet;

import be.esmay.atlas.common.network.packet.packets.AuthenticationPacket;
import be.esmay.atlas.common.network.packet.packets.HandshakePacket;
import be.esmay.atlas.common.network.packet.packets.HeartbeatPacket;
import be.esmay.atlas.common.network.packet.packets.ServerAddPacket;
import be.esmay.atlas.common.network.packet.packets.ServerInfoUpdatePacket;
import be.esmay.atlas.common.network.packet.packets.ServerListPacket;
import be.esmay.atlas.common.network.packet.packets.ServerListRequestPacket;
import be.esmay.atlas.common.network.packet.packets.ServerRemovePacket;
import be.esmay.atlas.common.network.packet.packets.ServerUpdatePacket;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class PacketRegistry {
    
    private static final Map<Integer, Supplier<? extends Packet>> PACKET_SUPPLIERS = new HashMap<>();
    private static final Map<Class<? extends Packet>, Integer> PACKET_IDS = new HashMap<>();
    
    static {
        registerPacket(0x01, HandshakePacket.class, HandshakePacket::new);
        registerPacket(0x02, AuthenticationPacket.class, AuthenticationPacket::new);
        registerPacket(0x03, HeartbeatPacket.class, HeartbeatPacket::new);
        registerPacket(0x10, ServerUpdatePacket.class, ServerUpdatePacket::new);
        registerPacket(0x11, ServerListPacket.class, ServerListPacket::new);
        registerPacket(0x12, ServerAddPacket.class, ServerAddPacket::new);
        registerPacket(0x13, ServerRemovePacket.class, ServerRemovePacket::new);
        registerPacket(0x14, ServerListRequestPacket.class, ServerListRequestPacket::new);
        registerPacket(0x20, ServerInfoUpdatePacket.class, ServerInfoUpdatePacket::new);
    }
    
    private static <T extends Packet> void registerPacket(int id, Class<T> clazz, Supplier<T> supplier) {
        PACKET_SUPPLIERS.put(id, supplier);
        PACKET_IDS.put(clazz, id);
    }
    
    public static Packet createPacket(int id) {
        Supplier<? extends Packet> supplier = PACKET_SUPPLIERS.get(id);
        if (supplier == null) {
            return null;
        }
        return supplier.get();
    }
    
    public static int getPacketId(Class<? extends Packet> clazz) {
        Integer id = PACKET_IDS.get(clazz);
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet class: " + clazz.getName());
        }
        return id;
    }
    
}