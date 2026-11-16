package com.loopers.interfaces.api.coupon;

import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 테스트가 한 운영 캠페인의 대기실 키 5개를 소유했는지 판단한다. Redis 대상 확인과 키 소유는 다른 조건이다.
 * 준비 때 5개가 모두 비어 있음을 확인한 경우만 소유하고, 소유했을 때만 정리에서 지운다. 하나라도 있으면(검사 실패 포함) 지우지 않는다.
 * 서버·worker 종료 확인은 호출자가 이 정리 전에 따로 한다.
 */
final class OwnedWaitingKeys {

    private final RedisTemplate<String, String> master;
    private final List<String> keys;
    private boolean owned;

    OwnedWaitingKeys(RedisTemplate<String, String> master, long campaignId) {
        this.master = master;
        this.keys = CouponAdmissionRedisRepository.waitingKeys(campaignId);
    }

    /** 모든 키가 없을 때만 소유한다. 남은 키는 다른 실행의 것일 수 있어 지우지 않고 실패한다. */
    void claim() {
        owned = false;
        List<String> existing = new ArrayList<>();
        for (String key : keys) {
            if (master.type(key) != DataType.NONE) {
                existing.add(key);
            }
        }
        if (!existing.isEmpty()) {
            throw new IllegalStateException("시작 전 대기실 키가 비어 있어야 한다. 지우지 않는다. " + existing);
        }
        owned = true;
    }

    /** 소유한 경우만 지운다. */
    void release() {
        if (owned) {
            master.delete(keys);
            owned = false;
        }
    }
}
