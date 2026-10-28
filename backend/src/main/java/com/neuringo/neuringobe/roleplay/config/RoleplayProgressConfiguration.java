package com.neuringo.neuringobe.roleplay.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoleplayProgressProperties.class)
public class RoleplayProgressConfiguration {}
