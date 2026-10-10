package com.fintechplatform.paycore.pin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PinProperties.class)
public class PinConfiguration {
}
