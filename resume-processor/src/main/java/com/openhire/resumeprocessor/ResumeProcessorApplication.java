package com.openhire.resumeprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.openhire")
public class ResumeProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResumeProcessorApplication.class, args);
    }
}
