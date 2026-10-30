package com.loopers.purchaseactivity.config;

import com.loopers.purchaseactivity.infrastructure.ProductPurchaseActivityJpaRepository;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackageClasses = ProductPurchaseActivityJpaRepository.class)
public class PurchaseActivityPersistenceConfiguration {
}
