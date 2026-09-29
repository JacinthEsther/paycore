package com.fintechplatform.paycore.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Everything in this package is active only when
 * {@code paycore.demo.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
@EnableConfigurationProperties(DemoProperties.class)
public class DemoConfiguration implements WebMvcConfigurer {

    private final DemoAdminGuard demoAdminGuard;

    public DemoConfiguration(DemoAdminGuard demoAdminGuard) {
        this.demoAdminGuard = demoAdminGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(demoAdminGuard);
    }
}
