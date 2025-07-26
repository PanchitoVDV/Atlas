package be.esmay.atlas.base.api.dto;

import be.esmay.atlas.base.activity.ActivityType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityResponse {

    private String id;
    private String serverId;
    private String serverName;
    private String groupName;
    private ActivityType activityType;
    
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    private String triggeredBy;
    private String description;
    private Map<String, Object> metadata;
}