package com.loopers.experiment.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RedisQueueExperimentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisQueueExperimentApplication.class, args);
    }
}
