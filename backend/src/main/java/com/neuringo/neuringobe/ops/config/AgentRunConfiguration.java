package com.neuringo.neuringobe.ops.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 실행 기록 저장·조회·보관 정리는 neuringo.agent-runs.enabled 가 true 일 때만 빈으로 만들어진다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AgentRunProperties.class)
public class AgentRunConfiguration {}
