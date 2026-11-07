package com.neuringo.neuringobe.roleplay.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 제품의 글자 수를 임의 확정하지 않고 배포별 처리 상한을 명시적으로 받는다. */
@Validated
@ConfigurationProperties("roleplay.text")
public record RoleplayTextProperties(@NotNull @Min(1) Integer maximumCharacters) {}
