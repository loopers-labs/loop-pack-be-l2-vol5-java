package com.loopers.config;

import com.loopers.payment.adapter.out.pg.PgProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PgProperties.class)
public class PgConfig {}
