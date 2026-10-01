package com.fintechplatform.paycore.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP when Spring Boot has configured a JavaMailSender (it does whenever
 * spring.mail.host is set), otherwise the logging sender.
 */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {

    @Bean
    public EmailSender emailSender(
            ObjectProvider<JavaMailSender> mailSender,
            NotificationProperties properties
    ) {
        JavaMailSender smtp = mailSender.getIfAvailable();

        return smtp != null
                ? new SmtpEmailSender(smtp, properties.getFrom())
                : new LoggingEmailSender();
    }
}
