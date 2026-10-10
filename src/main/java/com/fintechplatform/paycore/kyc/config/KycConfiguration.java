package com.fintechplatform.paycore.kyc.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({KycProperties.class, TierUpgradeProperties.class})
public class KycConfiguration {
}
