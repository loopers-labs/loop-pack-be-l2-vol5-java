package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueGate;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis 대신 이 앱의 메모리에 판단 정보를 두는 비교용 구현. 규칙은 {@link RedisCouponIssueGate}의 Lua와 같다.
 * 캠페인마다 한 객체를 잠가 판단과 기록 사이에 다른 요청이 끼어들지 못하게 한다(서버 한 대 안에서만).
 * 상태는 서버마다 따로 있고 재시작하면 사라진다. 만료 시각은 쓰지 않는다.
 */
@Component
@ConditionalOnProperty(name = "commerce.coupon.issue-gate.store", havingValue = "LOCAL")
public class LocalCouponIssueGate implements CouponIssueGate {
    private static final String ACCEPTED = "A";

    private final Map<Long, Campaign> campaigns = new ConcurrentHashMap<>();

    @Override
    public Entry enter(Long campaignId, Long userId, Instant now) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return new Entry(Decision.NOT_PREPARED, null);
        }
        synchronized (campaign) {
            String state = campaign.users.get(userId);
            if (ACCEPTED.equals(state)) {
                return new Entry(Decision.ALREADY_ACCEPTED, null);
            }
            if (state != null) {
                return new Entry(Decision.ALREADY_RESERVED, null);
            }
            if (!now.isBefore(campaign.endAt)) {
                return new Entry(Decision.ENDED, null);
            }
            if (campaign.remaining <= 0) {
                return new Entry(Decision.SOLD_OUT, null);
            }
            String reservation = "R:" + UUID.randomUUID();
            campaign.remaining--;
            campaign.users.put(userId, reservation);
            return new Entry(Decision.PASSED, reservation);
        }
    }

    /** 앱 메모리 판단은 서버 한 대 비교용이라 작업 큐를 지원하지 않는다. */
    @Override
    public Entry enqueue(Long campaignId, Long userId, Instant now) {
        throw new UnsupportedOperationException("Local coupon gate does not support the issue queue");
    }

    @Override
    public UserState state(Long campaignId, Long userId) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return UserState.NONE;
        }
        synchronized (campaign) {
            String value = campaign.users.get(userId);
            if (value == null) return UserState.NONE;
            return ACCEPTED.equals(value) ? UserState.ACCEPTED : UserState.RESERVED;
        }
    }

    @Override
    public boolean confirm(Long campaignId, Long userId, String reservation, boolean created) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return false;
        }
        synchronized (campaign) {
            if (!reservation.equals(campaign.users.get(userId))) {
                return false;
            }
            if (!created) {
                campaign.remaining++;
            }
            campaign.users.put(userId, ACCEPTED);
            return true;
        }
    }

    @Override
    public void cancel(Long campaignId, Long userId, String reservation) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return;
        }
        synchronized (campaign) {
            if (reservation.equals(campaign.users.get(userId))) {
                campaign.users.remove(userId);
                campaign.remaining++;
            }
        }
    }

    @Override
    public void cancelFinally(Long campaignId, Long userId, String reservation) {
        Campaign campaign = campaigns.get(campaignId);
        if (campaign == null) {
            return;
        }
        synchronized (campaign) {
            if (reservation.equals(campaign.users.get(userId))) {
                campaign.users.put(userId, "C");
                campaign.remaining++;
            }
        }
    }

    @Override
    public void prepare(Long campaignId, int remaining, Collection<Long> issuedUserIds, Instant endAt, Instant expireAt) {
        Campaign campaign = new Campaign(remaining, endAt);
        issuedUserIds.forEach(userId -> campaign.users.put(userId, ACCEPTED));
        campaigns.put(campaignId, campaign);
    }

    /** 한 캠페인의 남은 수와 사용자 상태("A" 또는 "R:UUID"). 이 객체를 잠근 채로만 읽고 바꾼다. */
    private static final class Campaign {
        private final Instant endAt;
        private final Map<Long, String> users = new HashMap<>();
        private int remaining;

        private Campaign(int remaining, Instant endAt) {
            this.remaining = remaining;
            this.endAt = endAt;
        }
    }
}
