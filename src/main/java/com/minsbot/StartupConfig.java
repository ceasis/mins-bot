package com.minsbot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Startup optimization: async task executor for non-critical initialization.
 */
@Configuration
public class StartupConfig {

    @Bean(name = "startupExecutor")
    public Executor startupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("startup-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
