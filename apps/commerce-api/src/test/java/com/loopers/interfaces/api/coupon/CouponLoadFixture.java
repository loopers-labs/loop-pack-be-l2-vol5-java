package com.loopers.interfaces.api.coupon;

import com.loopers.application.order.OrderPaymentQueryService;
import com.loopers.application.order.OrderPaymentStockService;
import com.loopers.application.order.OrderService;
import com.loopers.application.order.PaymentConfirmationConfiguration;
import com.loopers.application.product.ProductListReader;
import com.loopers.application.product.ProductListSingleFlight;
import com.loopers.application.product.ProductQueryService;
import com.loopers.application.user.UserResolver;
import com.loopers.config.AdminBoundaryConfig;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserIdentityRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.brand.BrandRepositoryImpl;
import com.loopers.infrastructure.order.OrderRepositoryImpl;
import com.loopers.infrastructure.payment.PaymentAttemptRepositoryImpl;
import com.loopers.infrastructure.payment.PaymentFingerprintConfiguration;
import com.loopers.infrastructure.product.ProductListRevisionRepositoryImpl;
import com.loopers.infrastructure.product.ProductQueryRepositoryImpl;
import com.loopers.infrastructure.product.ProductRepositoryImpl;
import com.loopers.infrastructure.product.cache.ProductListCacheConfiguration;
import com.loopers.infrastructure.user.UserRepositoryImpl;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.interfaces.api.commerce.CommerceWebConfiguration;
import com.loopers.interfaces.api.order.OrderV1Controller;
import com.loopers.interfaces.api.product.ProductController;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Optional;

/** Same HTTP server, Redis, MySQL and pool for the baseline and the loaded measurement. */
@Testcontainers
@DirtiesContext
@ActiveProfiles("coupon-load-experiment")
@SpringBootTest(classes = CouponLoadFixture.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.timezone.default_storage=NORMALIZE_UTC", "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "server.address=127.0.0.1", "spring.data.redis.repositories.enabled=false", "management.health.redis.enabled=false",
        "commerce.coupon.admission.enabled=true", "commerce.coupon.worker.enabled=true", "commerce.coupon.worker.capacity=3",
        "commerce.product-list-cache.enabled=false", "commerce.test-pg.enabled=false"})
abstract class CouponLoadFixture {
    static final long CAMPAIGN = 8910;
    static final long PRODUCT = 1;
    static final long ORDER = 1;
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("w8_coupon_load");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @LocalServerPort int port;
    @Autowired CouponLoadProbe probe;

    static JdbcTemplate observer() {
        return new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @DynamicPropertySource
    static void ownedStores(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.database", () -> 0);
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
        registry.add("commerce.coupon.admission.campaign-ids", () -> CAMPAIGN);
        registry.add("commerce.coupon.worker.campaign-ids", () -> CAMPAIGN);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("coupon-load-experiment")
    @EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
    @EntityScan({"com.loopers.domain.brand", "com.loopers.domain.user", "com.loopers.domain.like",
        "com.loopers.domain.product", "com.loopers.domain.order", "com.loopers.domain.payment",
        "com.loopers.domain.coupon", "com.loopers.infrastructure.brand"})
    @EnableJpaRepositories({"com.loopers.infrastructure.brand", "com.loopers.infrastructure.product",
        "com.loopers.infrastructure.user", "com.loopers.infrastructure.order", "com.loopers.infrastructure.payment"})
    @ComponentScan(basePackages = {"com.loopers.application.coupon", "com.loopers.interfaces.api.coupon",
        "com.loopers.infrastructure.coupon", "com.loopers.config.coupon", "com.loopers.config.jackson"},
        excludeFilters = @ComponentScan.Filter(TestComponent.class))
    @Import({RedisConfig.class, AdminBoundaryConfig.class, CommerceErrors.class, CommerceWebConfiguration.class,
        UserResolver.class, UserRepositoryImpl.class, ProductController.class, ProductQueryService.class,
        ProductQueryRepositoryImpl.class, ProductListReader.class, ProductListRevisionRepositoryImpl.class,
        ProductListCacheConfiguration.class, ProductListSingleFlight.class, OrderV1Controller.class,
        OrderService.class, OrderPaymentQueryService.class, OrderPaymentStockService.class,
        PaymentConfirmationConfiguration.class, BrandRepositoryImpl.class, OrderRepositoryImpl.class,
        ProductRepositoryImpl.class, PaymentAttemptRepositoryImpl.class, PaymentFingerprintConfiguration.class})
    static class Application {
        @Bean(destroyMethod = "close")
        HikariDataSource loadPool() {
            CouponLoadDatabase.seed(MYSQL, CAMPAIGN, 100_000, 100);
            var config = new HikariConfig();
            config.setJdbcUrl(MYSQL.getJdbcUrl());
            config.setUsername(MYSQL.getUsername());
            config.setPassword(MYSQL.getPassword());
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(5);
            config.setConnectionTimeout(3_000);
            config.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=3");
            return new HikariDataSource(config);
        }

        @Bean
        CouponLoadProbe loadProbe() {
            return new CouponLoadProbe();
        }

        @Bean
        @Primary
        DataSource observedLoadDataSource(HikariDataSource loadPool, CouponLoadProbe probe) {
            return probe.observe(loadPool);
        }

        @Bean
        UserIdentityRepository loadIdentities() {
            return new UserIdentityRepository() {
                @Override
                public Optional<UserIdentity> findByExternalId(String externalId) {
                    if (externalId == null || !externalId.startsWith("applicant-")) {
                        return Optional.empty();
                    }
                    try {
                        long id = Long.parseLong(externalId, 10, externalId.length(), 10);
                        return id >= 1 && id <= 100_000 ? Optional.of(new UserIdentity(id, UserRole.CUSTOMER)) : Optional.empty();
                    } catch (NumberFormatException invalidId) {
                        return Optional.empty();
                    }
                }

                @Override
                public Optional<String> findExternalId(long userId) {
                    return userId >= 1 && userId <= 100_000 ? Optional.of("applicant-" + userId) : Optional.empty();
                }
            };
        }
    }
}
