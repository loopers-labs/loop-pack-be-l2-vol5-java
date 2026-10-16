package com.loopers.infrastructure.product.cache;

import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductListCacheConfigurationTest {
    @Test
    void actualLocalProfileKeepsCacheOffUnlessExplicitlyEnabled() {
        ApplicationContextRunner local = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("spring.profiles.active=local");
        local.run(context -> {
            assertThat(context).hasNotFailed();
            ProductListCacheProperties properties = context.getBean(ProductListCacheProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.namespace()).isEqualTo("commerce:local:products:v1");
            assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(60));
        });
        local.withPropertyValues("commerce.product-list-cache.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProductListCacheProperties.class).enabled()).isTrue();
        });
    }

    @Test
    void configurationDefaultsAreBoundAndCanBeOverridden() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class).run(context -> {
            ProductListCacheProperties properties = context.getBean(ProductListCacheProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.namespace()).isEqualTo("commerce:local:products:v1");
            assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.commandTimeout()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(100));
        });
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("commerce.product-list-cache.enabled=false").run(context ->
                assertThat(context.getBean(ProductListCacheProperties.class).enabled()).isFalse());
    }

    @Test
    void dedicatedConnectionUsesOnlyTheConfiguredMasterAndIndependentTimeouts() {
        var properties = new ProductListCacheProperties(true, "commerce:test:products:v1", Duration.ofSeconds(60),
            Duration.ofMillis(80), Duration.ofMillis(120));
        var factory = new ProductListCacheConfiguration().connectionFactory(new RedisProperties(2,
            new RedisNodeInfo("master-host", 6379), List.of(new RedisNodeInfo("replica-host", 6380))), properties);

        assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("master-host");
        assertThat(factory.getStandaloneConfiguration().getPort()).isEqualTo(6379);
        assertThat(factory.getStandaloneConfiguration().getDatabase()).isEqualTo(2);
        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(120));
        ClientOptions options = factory.getClientConfiguration().getClientOptions().orElseThrow();
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(80));
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Test
    void rejectsEmptyNamespaceAndZeroOrNegativeExpiryAndTimeouts() {
        Duration valid = Duration.ofMillis(100);
        assertThatThrownBy(() -> new ProductListCacheProperties(true, "", valid, valid, valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductListCacheProperties(true, "bad namespace", valid, valid, valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductListCacheProperties(true, "test", Duration.ZERO, valid, valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductListCacheProperties(true, "test", valid, Duration.ofMillis(-1), valid))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductListCacheProperties(true, "test", valid, valid, Duration.ofNanos(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProductListCacheProperties.class)
    static class PropertiesOnly {
    }
}
