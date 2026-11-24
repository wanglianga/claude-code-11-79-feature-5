package com.ctrial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CTrialApplication {
    public static void main(String[] args) {
        SpringApplication.run(CTrialApplication.class, args);
    }
}
