package be.esmay.atlas.base.template;

import lombok.Data;

import java.time.Instant;

@Data
public class TemplateMetadata {

    private final String path;
    private final TemplateSource source;
    private final String checksum;
    private final long size;
    private final Instant lastModified;
    private final Instant cacheExpiration;

}