package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.AdmissionSettings;
import com.loopers.domains.coupon.app.port.AdmissionStatus;
import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// 수용 닫힘 상태는 이 행에만 둔다(D130·D133). 행이 없으면 닫힘이다(기본 닫힘). 닫기·열기는 각자 짧은 트랜잭션이다.
// 열기만 epoch를 1 올린다. 닫기는 epoch를 바꾸지 않는다(D138).
@Repository
public class CouponAdmissionSettingsAdapter implements CouponAdmissionSettings {
    private static final int CLOSED_REASON_LENGTH = 500;

    private final CouponAdmissionSettingsJpaRepository couponAdmissionSettingsJpaRepository;

    public CouponAdmissionSettingsAdapter(CouponAdmissionSettingsJpaRepository couponAdmissionSettingsJpaRepository) {
        this.couponAdmissionSettingsJpaRepository = couponAdmissionSettingsJpaRepository;
    }

    @Override
    public AdmissionSettings current() {
        try {
            return couponAdmissionSettingsJpaRepository.findById(CouponAdmissionSettingsJpaEntity.SINGLE_ROW_ID)
                .map(entity -> new AdmissionSettings(entity.capacity(), entity.open(), entity.epoch()))
                .orElse(new AdmissionSettings(0, false, 0));
        } catch (RuntimeException e) {
            // DB 연결 실패는 DataAccessException이 아니라 리포지토리 트랜잭션 시작 실패(TransactionException)로도 온다. 모두 포트 예외로 바꾼다(D123).
            throw new AdmissionUnavailableException("수용 설정을 읽지 못함: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void close(String reason, Instant at) {
        String trimmed = reason.length() <= CLOSED_REASON_LENGTH ? reason : reason.substring(0, CLOSED_REASON_LENGTH);
        couponAdmissionSettingsJpaRepository.close(CouponAdmissionSettingsJpaEntity.SINGLE_ROW_ID, trimmed, at);
    }

    @Override
    public AdmissionStatus status() {
        return couponAdmissionSettingsJpaRepository.findById(CouponAdmissionSettingsJpaEntity.SINGLE_ROW_ID)
            .map(entity -> new AdmissionStatus(entity.open(), entity.closedReason(), entity.closedAt(), entity.epoch()))
            .orElse(new AdmissionStatus(false, null, null, 0));
    }

    @Override
    @Transactional
    public long open(Instant at) {
        couponAdmissionSettingsJpaRepository.open(CouponAdmissionSettingsJpaEntity.SINGLE_ROW_ID, at);
        return couponAdmissionSettingsJpaRepository.findById(CouponAdmissionSettingsJpaEntity.SINGLE_ROW_ID)
            .map(CouponAdmissionSettingsJpaEntity::epoch)
            .orElseThrow(() -> new IllegalStateException("수용 설정 행이 없어 열 수 없습니다."));
    }
}
