package com.fintechplatform.paycore.funding.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FundingProperties.class)
public class FundingConfiguration {
}
