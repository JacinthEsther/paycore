package com.fintechplatform.paycore.bills.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BillProperties.class)
public class BillConfiguration {

    @Bean
    @ConditionalOnProperty(name = "paycore.bills.provider", havingValue = "vtpass")
    RestClient vtpassRestClient(RestClient.Builder builder, BillProperties properties) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(45));

        return builder
                .baseUrl(properties.getVtpass().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
