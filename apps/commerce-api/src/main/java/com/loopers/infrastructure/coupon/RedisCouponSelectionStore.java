package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponSelectionStore;
import com.loopers.application.coupon.CouponSelectionUnavailableException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedisCouponSelectionStore implements CouponSelectionStore {
    private static final RedisScript<String> COMMAND = RedisScript.of(
        new ClassPathResource("redis/coupon-selection.lua"), String.class);
    private final RedisTemplate<String, String> master;
    private final Map<Long, String> openedCampaigns = new ConcurrentHashMap<>();

    public RedisCouponSelectionStore(RedisTemplate<String, String> master) {
        this.master = master;
    }

    @Override
    public void open(long campaignId, int totalQuota, List<Long> reservedUsers, boolean active) {
        positive(campaignId);
        close(campaignId);
        if (totalQuota < 0 || reservedUsers.size() > totalQuota) {
            throw new IllegalArgumentException("Invalid campaign quota or reservation snapshot");
        }
        var args = new ArrayList<String>();
        args.add("open");
        args.add(UUID.randomUUID().toString());
        args.add(Integer.toString(totalQuota));
        args.add(active ? "1" : "0");
        reservedUsers.forEach(user -> args.add(positive(user)));
        String epoch = execute(campaignId, args.toArray());
        openedCampaigns.put(campaignId, epoch);
    }

    @Override
    public void close(long campaignId) {
        openedCampaigns.remove(campaignId);
    }

    @Override
    public boolean isOpen(long campaignId) {
        return openedCampaigns.containsKey(campaignId);
    }

    @Override
    public Selection select(long campaignId, long userId) {
        return Selection.valueOf(whenOpen(campaignId, "select", positive(userId)));
    }

    @Override
    public List<Long> pending(long campaignId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Pending batch size must be positive");
        }
        String users = whenOpen(campaignId, "pending", Integer.toString(limit));
        return users.isEmpty() ? List.of() : Arrays.stream(users.split(",")).map(Long::valueOf).toList();
    }

    @Override
    public void complete(long campaignId, long userId) {
        whenOpen(campaignId, "complete", positive(userId));
    }

    private String whenOpen(long campaignId, String command, String argument) {
        String epoch = openedCampaigns.get(campaignId);
        if (epoch == null) {
            throw new CouponSelectionUnavailableException();
        }
        try {
            return execute(campaignId, command, epoch, argument);
        } catch (CouponSelectionUnavailableException failure) {
            openedCampaigns.remove(campaignId, epoch);
            throw failure;
        }
    }

    private String execute(long campaignId, Object... arguments) {
        String prefix = "commerce:coupon-selection:v1:{" + campaignId + "}:";
        try {
            String result = master.execute(COMMAND, List.of(prefix + "state", prefix + "work"), arguments);
            if (result == null || result.equals("UNAVAILABLE")) {
                throw new CouponSelectionUnavailableException();
            }
            return result;
        } catch (DataAccessException failure) {
            throw new CouponSelectionUnavailableException(failure);
        }
    }

    private static String positive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Coupon selection identifiers must be positive");
        }
        return Long.toString(id);
    }
}
