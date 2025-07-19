package be.esmay.atlas.base.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FileListResponse {
    
    private final String path;
    private final List<FileInfo> files;
}