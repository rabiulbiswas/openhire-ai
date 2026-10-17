package com.openhire.aiworkers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.openhire")
public class AiWorkersServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiWorkersServiceApplication.class, args);
    }
}
