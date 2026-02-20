package com.minsbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MinsbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(MinsbotApplication.class, args);
    }
}
