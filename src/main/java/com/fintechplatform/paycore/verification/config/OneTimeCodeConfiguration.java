package com.fintechplatform.paycore.verification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OneTimeCodeProperties.class)
public class OneTimeCodeConfiguration {
}
