package com.neuringo.neuringobe.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConsentProperties.class)
public class ConsentConfiguration {}
