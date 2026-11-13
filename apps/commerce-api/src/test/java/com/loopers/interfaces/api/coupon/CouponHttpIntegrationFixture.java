package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.user.UserResolver;
import com.loopers.config.AdminBoundaryConfig;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserIdentityRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.product.cache.OwnedRedisContainer;
import com.loopers.infrastructure.user.UserRepositoryImpl;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.interfaces.api.commerce.CommerceWebConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

abstract class CouponHttpIntegrationFixture {
    static final long SCHEDULED_CAMPAIGN = 1099;
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w8_coupon_http_test");
    @Container
    static final OwnedRedisContainer REDIS = new OwnedRedisContainer();

    @DynamicPropertySource
    static void useOnlyOwnedRedis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", REDIS::getFirstMappedPort);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", REDIS::getFirstMappedPort);
    }

    @Autowired TestRestTemplate http;

    @BeforeEach
    void disableAutomaticHttpResubmission() {
        // Observe the first POST response, even when it is 503.
        var singleRequest = new SimpleClientHttpRequestFactory();
        singleRequest.setConnectTimeout(2_000);
        singleRequest.setReadTimeout(20_000);
        http.getRestTemplate().setRequestFactory(singleRequest);
    }

    ResponseEntity<JsonNode> apply(long campaignId, long userId) {
        var headers = new HttpHeaders();
        headers.set("X-USER-ID", "applicant-" + userId);
        return http.postForEntity("/api/v1/coupon-campaigns/" + campaignId + "/issues", new HttpEntity<>(headers),
            JsonNode.class);
    }

    static void insertCampaign(JdbcTemplate jdbc, long campaignId, int quota) {
        jdbc.update("""
            INSERT INTO coupon_campaign (id, name, active, total_quota, benefit_description)
            VALUES (?, 'isolated campaign', TRUE, ?, 'fixed benefit')
            """, campaignId, quota);
    }

    static DriverManagerDataSource ownedDataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    static void pingOwnedRedis(int port) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(REDIS.getHost(), port), 1000);
            socket.setSoTimeout(750);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            if (!"+PONG".equals(reader.readLine())) {
                throw new IOException("The isolated Redis endpoint did not acknowledge PING");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("coupon-admission-http-test")
    @EnableAutoConfiguration
    @EntityScan({"com.loopers.domain.coupon", "com.loopers.domain.user"})
    @EnableJpaRepositories("com.loopers.infrastructure.user")
    @ComponentScan(basePackages = {"com.loopers.interfaces.api.coupon", "com.loopers.application.coupon",
        "com.loopers.infrastructure.coupon", "com.loopers.config.jackson", "com.loopers.config.coupon"},
        excludeFilters = @ComponentScan.Filter(TestComponent.class))
    @Import({RedisConfig.class,
        UserResolver.class, UserRepositoryImpl.class, AdminBoundaryConfig.class, CommerceErrors.class, CommerceWebConfiguration.class})
    static class IsolatedApplication {
        @Bean
        CouponHttpDatabaseProbe dataSource() {
            var source = ownedDataSource();
            new ResourceDatabasePopulator(new ClassPathResource("db/schema/002-user.sql"),
                new ClassPathResource("db/schema/013-coupon-issuance.sql"),
                new ClassPathResource("db/schema/014-coupon-waiting-index.sql")).execute(source);
            var fixture = new JdbcTemplate(source);
            fixture.batchUpdate("INSERT INTO `user` (id, point_balance, created_at, updated_at) "
                + "VALUES (?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                LongStream.rangeClosed(1, 101).mapToObj(id -> new Object[]{id}).toList());
            insertCampaign(fixture, SCHEDULED_CAMPAIGN, 1);
            return new CouponHttpDatabaseProbe(source);
        }

        @Bean
        UserIdentityRepository applicantIdentities() {
            Map<String, UserIdentity> applicants = LongStream.rangeClosed(1, 101).boxed().collect(Collectors.toMap(
                id -> "applicant-" + id, id -> new UserIdentity(id, UserRole.CUSTOMER)));
            return new UserIdentityRepository() {
                @Override
                public Optional<UserIdentity> findByExternalId(String id) {
                    return Optional.ofNullable(applicants.get(id));
                }

                @Override
                public Optional<String> findExternalId(long userId) {
                    return Optional.of("applicant-" + userId).filter(applicants::containsKey);
                }
            };
        }
    }
}
