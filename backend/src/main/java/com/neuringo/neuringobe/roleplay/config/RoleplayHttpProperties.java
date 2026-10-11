package com.neuringo.neuringobe.roleplay.config;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.ApprovedNotice;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("roleplay.http")
public record RoleplayHttpProperties(
        @NotNull Path spoolDirectory,
        @NotNull @Min(1) Integer maximumUploadBytes,
        @NotNull @Min(1) Long maximumRequestBytes,
        @NotEmpty Set<AudioFormat> allowedFormats,
        @NotNull @Min(1) Integer workerCount,
        @NotNull @Min(0) Integer queueCapacity,
        List<ApprovedNotice> notices,
        Duration turnBudget) {
    public RoleplayHttpProperties {
        notices = notices == null ? List.of() : List.copyOf(notices);
        turnBudget = turnBudget == null ? RoleplayTurnDeadline.MAX_DURATION : turnBudget;
        if (turnBudget.isNegative()
                || turnBudget.isZero()
                || turnBudget.compareTo(RoleplayTurnDeadline.MAX_DURATION) > 0)
            throw new IllegalArgumentException(
                    "HTTP turn budget must be positive and at most 60 seconds");
        if (maximumRequestBytes != null
                && maximumUploadBytes != null
                && maximumRequestBytes < maximumUploadBytes)
            throw new IllegalArgumentException(
                    "Multipart request bound cannot be below audio bound");
    }

    @Override
    public String toString() {
        return "RoleplayHttpProperties[content=redacted]";
    }
}
