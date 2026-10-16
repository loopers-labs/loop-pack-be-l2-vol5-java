package com.loopers.config.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.SliceResponse;
import com.loopers.product.interfaces.ProductV1Dto;
import com.loopers.product.interfaces.ProductV1Controller;
import io.micrometer.core.instrument.MeterRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

/** Fixed-data performance experiment only. No mutation/invalidation contract. Disabled by default. */
@Configuration(proxyBeanMethods = false)
@Profile("experiment")
@ConditionalOnProperty(name = "experiment.latest-cache.enabled", havingValue = "true")
public class ExperimentLatestCache {
    public static final String KEY = "products:list:v2:sort=latest:brand=all:page=0:size=20";
    // Bounds the lifetime of trial data, not a production freshness policy.
    static final Duration TTL = Duration.ofMinutes(10);
    private static final TypeReference<ApiResponse<SliceResponse<ProductV1Dto.CustomerProductResponse>>> TYPE =
        new TypeReference<>() { };
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public ExperimentLatestCache(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        ObjectMapper json, MeterRegistry metrics,
        @org.springframework.beans.factory.annotation.Value("${catalog.cache.enabled:false}") boolean catalogCache
    ) {
        if (catalogCache) throw new IllegalStateException("Disable experiment.latest-cache when catalog.cache is enabled");
        this.redis = redis;
        this.json = json;
        this.metrics = metrics;
    }

    @Bean
    static BeanPostProcessor latestCacheProxy(ObjectProvider<ExperimentLatestCache> cache) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!ProductV1Controller.class.isAssignableFrom(AopUtils.getTargetClass(bean))) return bean;
                ProxyFactory proxy = new ProxyFactory(bean);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    if (!invocation.getMethod().getName().equals("getProducts")) return invocation.proceed();
                    // MVC resolves/validates Requester before invoking this method; auth is never cached.
                    Object[] args = invocation.getArguments();
                    if (args[1] != null || (args[2] != null && !"latest".equals(args[2]))
                        || !Integer.valueOf(0).equals(args[3]) || !Integer.valueOf(20).equals(args[4])) {
                        return invocation.proceed();
                    }
                    return cache.getObject().read(invocation::proceed);
                });
                return proxy.getProxy();
            }
        };
    }

    Object read(Loader loader) throws Throwable {
        try {
            String value = redis.opsForValue().get(KEY);
            if (value != null) {
                var cached = json.readValue(value, TYPE);
                metrics.counter("experiment.latest.cache", "result", "hit").increment();
                return cached;
            }
        } catch (Exception failure) {
            metrics.counter("experiment.latest.cache", "result", "read_error").increment();
            return loader.load();
        }
        metrics.counter("experiment.latest.cache", "result", "miss").increment();
        Object response = loader.load();
        try {
            redis.opsForValue().set(KEY, json.writeValueAsString(response), TTL);
            metrics.counter("experiment.latest.cache", "result", "fill").increment();
        } catch (Exception failure) {
            metrics.counter("experiment.latest.cache", "result", "write_error").increment();
        }
        return response;
    }

    @FunctionalInterface
    interface Loader {
        Object load() throws Throwable;
    }
}
