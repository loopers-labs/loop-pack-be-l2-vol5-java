package com.loopers.application.coupon;

import java.util.List;

public interface CouponSelectionStore {
    enum Selection { NEW, EXISTING, FULL, INACTIVE }

    default void open(long campaignId, int totalQuota, List<Long> reservedUsers) {
        open(campaignId, totalQuota, reservedUsers, true);
    }

    void open(long campaignId, int totalQuota, List<Long> reservedUsers, boolean active);

    void close(long campaignId);

    boolean isOpen(long campaignId);

    Selection select(long campaignId, long userId);

    List<Long> pending(long campaignId, int limit);

    void complete(long campaignId, long userId);
}
