package com.telecom.casemanagement.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class MetricsConfig {

    @Value("${spring.profiles.active:unknown}")
    private String activeProfile;

    /**
     * Adds global tags to every metric so Grafana dashboards can filter by
     * environment (dev / staging / prod) and application name.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> globalTags() {
        return registry -> registry.config()
                .commonTags(List.of(
                    Tag.of("application", "flowops"),
                    Tag.of("environment", activeProfile)
                ));
    }

    /** Enables @Timed annotation support on Spring beans. */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
