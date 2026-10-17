package com.openhire.talent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.openhire")
public class TalentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TalentServiceApplication.class, args);
    }
}
