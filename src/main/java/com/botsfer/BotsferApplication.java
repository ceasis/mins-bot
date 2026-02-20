package com.botsfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BotsferApplication {

    public static void main(String[] args) {
        SpringApplication.run(BotsferApplication.class, args);
    }
}
