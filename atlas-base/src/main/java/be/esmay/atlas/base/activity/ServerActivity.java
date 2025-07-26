package be.esmay.atlas.base.activity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "server_activities", indexes = {
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_server_id", columnList = "server_id"),
    @Index(name = "idx_group_name", columnList = "group_name"),
    @Index(name = "idx_activity_type", columnList = "activity_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerActivity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "server_id", length = 100)
    private String serverId;

    @Column(name = "server_name", length = 100)
    private String serverName;

    @Column(name = "group_name", length = 50)
    private String groupName;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 50)
    private ActivityType activityType;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    @Lob
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "description", length = 500)
    private String description;

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = LocalDateTime.now();
        }
        if (this.id == null) {
            this.id = java.util.UUID.randomUUID().toString();
        }
    }
}