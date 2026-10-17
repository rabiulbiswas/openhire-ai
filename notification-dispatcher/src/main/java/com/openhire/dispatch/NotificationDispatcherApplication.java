package com.openhire.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.openhire")
public class NotificationDispatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationDispatcherApplication.class, args);
    }
}
