package com.openhire.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.openhire")
public class AiGatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiGatewayServiceApplication.class, args);
    }
}
