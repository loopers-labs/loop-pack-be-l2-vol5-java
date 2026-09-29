package com.loopers.config;

import com.loopers.application.point.PointProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PointProperties.class)
public class PointConfig {}
