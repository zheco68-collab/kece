package com.kece.judging.control;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class JudgeControlConfig {
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("kece-scheduled-");
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler judgeClaimExecutor() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("judge-claims-");
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler judgeConnectionExecutor() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("judge-connections-");
        return scheduler;
    }
}
