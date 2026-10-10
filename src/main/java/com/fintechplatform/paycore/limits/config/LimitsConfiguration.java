package com.fintechplatform.paycore.limits.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TierLimitProperties.class)
public class LimitsConfiguration {
}
