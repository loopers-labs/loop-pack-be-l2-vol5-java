package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueSemaphore;
import com.loopers.application.coupon.CouponIssueSemaphoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RedisCouponIssueSemaphore implements CouponIssueSemaphore {
    private static final Logger log = LoggerFactory.getLogger(RedisCouponIssueSemaphore.class);
    private static final List<String> KEYS = List.of("commerce:coupon-issue:v1:{global}:state",
        "commerce:coupon-issue:v1:{global}:tokens");
    private static final ClassPathResource LUA = new ClassPathResource("redis/coupon-issue-semaphore.lua");
    private static final RedisScript<String> OPEN = RedisScript.of(LUA, String.class);
    private static final RedisScript<Long> CHANGE = RedisScript.of(LUA, Long.class);

    private final RedisTemplate<String, String> master;
    private final String capacity;
    private final String owner = UUID.randomUUID().toString();
    private final Set<UUID> active = new HashSet<>();
    // Acquire unconfirmed (DB never entered), or release unconfirmed (work already ended).
    private final Set<UUID> uncertain = new HashSet<>();
    private String generation;
    private boolean open;

    public RedisCouponIssueSemaphore(RedisTemplate<String, String> master, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Coupon issuance capacity must be positive");
        }
        this.master = master;
        this.capacity = Integer.toString(capacity);
    }

    @Override
    public synchronized void open() {
        open = false;
        if (!active.isEmpty()) {
            throw new CouponIssueSemaphoreUnavailableException();
        }
        var args = new ArrayList<>(List.of("open", UUID.randomUUID().toString(), capacity, owner));
        uncertain.forEach(token -> args.add(token.toString()));
        generation = execute(OPEN, args.toArray());
        uncertain.clear();
        open = true;
        log.info("Opened coupon issuance semaphore owner={} epoch={}", owner, generation);
    }

    @Override
    public synchronized void close() {
        open = false;
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized boolean tryAcquire(UUID token) {
        Objects.requireNonNull(token);
        if (!open) {
            throw new CouponIssueSemaphoreUnavailableException();
        }
        boolean reconfirm = active.contains(token);
        long granted;
        try {
            granted = execute(CHANGE, reconfirm ? "confirm" : "acquire", generation, capacity, owner, token.toString());
        } catch (CouponIssueSemaphoreUnavailableException failure) {
            open = false;
            if (!reconfirm) {
                uncertain.add(token);
            }
            throw failure;
        }
        if (granted == 0L) {
            return false;
        }
        if (granted != 1L) {
            open = false;
            throw new CouponIssueSemaphoreUnavailableException();
        }
        active.add(token);
        return true;
    }

    @Override
    public synchronized void release(UUID token) {
        Objects.requireNonNull(token);
        boolean wasActive = active.remove(token);
        if (!wasActive && !uncertain.contains(token)) {
            return;
        }
        try {
            long removed = execute(CHANGE, "release", generation, capacity, owner, token.toString());
            if (wasActive && removed == 0L) {
                throw new CouponIssueSemaphoreUnavailableException();
            }
            uncertain.remove(token);
        } catch (CouponIssueSemaphoreUnavailableException failure) {
            open = false;
            uncertain.add(token);
            throw failure;
        }
    }

    private <T> T execute(RedisScript<T> script, Object... args) {
        try {
            T reply = master.execute(script, KEYS, args);
            if (reply == null) {
                throw new CouponIssueSemaphoreUnavailableException();
            }
            return reply;
        } catch (DataAccessException failure) {
            throw new CouponIssueSemaphoreUnavailableException(failure);
        }
    }
}
