package com.loopers.support.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CatalogSingleFlightTest {
    private CacheVersions versions() {
        return new CacheVersions(mock(org.springframework.jdbc.core.JdbcTemplate.class),
            new WriteInvalidation(false, mock(RedisTemplate.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }
    @Test
    @DisplayName("같은 miss의 대기자는 DB 트랜잭션 없이 기다린 뒤 자기 응답을 다시 구성한다")
    void followersWaitOutsideTransactionAndRecompose() throws Exception {
        var begins = new AtomicInteger();
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition def) { begins.incrementAndGet(); }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        var store = new ConcurrentHashMap<String, String>();
        var cached = mock(CachedJson.class);
        doAnswer(call -> {
            String key = call.getArgument(1);
            String value = store.get(key);
            if (value == null) {
                value = ((java.util.function.Supplier<String>) call.getArgument(2)).get();
                store.put(key, value);
            }
            return value;
        })
            .when(cached).load(anyString(), anyString(), any());
        var response = new ResponseCache(cached, true, new ObjectMapper(), versions());
        var transaction = new CatalogReadTransaction(manager, response, new CacheSingleFlight());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var originalCalls = new AtomicInteger();
        java.util.function.Supplier<String> read = () -> response.read( "products", CacheKeys.product(1L), new TypeReference<String>() { }, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            originalCalls.incrementAndGet(); entered.countDown(); CacheSingleFlightTest.waitFor(release); return "stock";
        });
        var owner = new FutureTask<>(() -> transaction.read(() -> read.get() + ":owner"));
        Thread.ofPlatform().start(owner);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var follower = new FutureTask<>(() -> transaction.read(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return read.get() + ":follower";
        }));
        Thread thread = Thread.ofPlatform().start(follower);
        try {
            CacheSingleFlightTest.awaitWaiting(thread);
            assertThat(begins.get()).isEqualTo(1);
        } finally { release.countDown(); }
        assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("stock:owner");
        assertThat(follower.get(5, TimeUnit.SECONDS)).isEqualTo("stock:follower");
        assertThat(originalCalls.get()).isEqualTo(1);
        assertThat(begins.get()).isEqualTo(1);
    }
}
