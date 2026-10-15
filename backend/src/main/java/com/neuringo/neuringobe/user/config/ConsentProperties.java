package com.neuringo.neuringobe.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 가입 때 동의를 받은 이용약관·개인정보 처리방침의 현재 버전. 화면은 동의 여부만 보내고 서버가 이 값을 찍어 저장한다. 약관이 바뀌면 여기만 고친다. */
@Validated
@ConfigurationProperties("neuringo.consent")
public record ConsentProperties(
        @NotBlank @Size(max = 20) String termsVersion,
        @NotBlank @Size(max = 20) String privacyVersion) {}
