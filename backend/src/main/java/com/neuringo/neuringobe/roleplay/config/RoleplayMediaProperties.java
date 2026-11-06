package com.neuringo.neuringobe.roleplay.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("roleplay.media")
public record RoleplayMediaProperties(
        String ffmpegExecutable, String ffprobeExecutable, Duration inspectionTimeout) {
    public RoleplayMediaProperties {
        ffmpegExecutable = ffmpegExecutable == null ? "ffmpeg" : ffmpegExecutable;
        ffprobeExecutable = ffprobeExecutable == null ? "ffprobe" : ffprobeExecutable;
        inspectionTimeout = inspectionTimeout == null ? Duration.ofSeconds(10) : inspectionTimeout;
    }
}
