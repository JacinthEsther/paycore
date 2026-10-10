package com.fintechplatform.paycore.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "paycore.devices")
public class DeviceProperties {

    private Duration coolingOff = Duration.ofHours(24);

    private BigDecimal coolingOffLimit = new BigDecimal("20000");

    public Duration getCoolingOff() {
        return coolingOff;
    }

    public void setCoolingOff(Duration coolingOff) {
        this.coolingOff = coolingOff;
    }

    public BigDecimal getCoolingOffLimit() {
        return coolingOffLimit;
    }

    public void setCoolingOffLimit(BigDecimal coolingOffLimit) {
        this.coolingOffLimit = coolingOffLimit;
    }
}
