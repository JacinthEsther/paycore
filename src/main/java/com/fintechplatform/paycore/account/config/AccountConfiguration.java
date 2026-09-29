package com.fintechplatform.paycore.account.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccountProperties.class)
public class AccountConfiguration {
}
