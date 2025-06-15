package be.esmay.atlas.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public final class PlayerInfo {

    private UUID uniqueId;
    private String name;
    private String currentServer;

    private long joinedAt;
    private long lastSeenAt;

}
