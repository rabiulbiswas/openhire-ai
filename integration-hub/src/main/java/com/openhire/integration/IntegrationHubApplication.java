package com.openhire.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.openhire")
@org.springframework.scheduling.annotation.EnableScheduling
public class IntegrationHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntegrationHubApplication.class, args);
    }
}
