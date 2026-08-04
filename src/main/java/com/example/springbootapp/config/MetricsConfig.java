package com.example.springbootapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter usersCreatedTotal(MeterRegistry registry) {
        return Counter.builder("users_created_total")
                .description("Total users created")
                .register(registry);
    }
}
