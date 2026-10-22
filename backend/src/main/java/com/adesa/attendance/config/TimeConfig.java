package com.adesa.attendance.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time source.
 *
 * <p>A single {@link Clock} bean keeps the application free of implicit
 * system-time and system-timezone lookups: attendance timestamps are always
 * produced here, and tests can pin the clock.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
