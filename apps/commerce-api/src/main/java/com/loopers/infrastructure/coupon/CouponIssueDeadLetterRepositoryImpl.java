package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponIssueDeadLetter;
import com.loopers.domain.coupon.CouponIssueDeadLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CouponIssueDeadLetterRepositoryImpl implements CouponIssueDeadLetterRepository {

    private static final int RECENT_LIMIT = 100;

    private final CouponIssueDeadLetterJpaRepository jpaRepository;

    @Override
    public boolean insertIfAbsent(CouponIssueDeadLetter deadLetter) {
        if (jpaRepository.existsByMessageKey(deadLetter.messageKey())) {
            return false;
        }
        try {
            jpaRepository.saveAndFlush(CouponIssueDeadLetterEntity.from(deadLetter));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Override
    public List<CouponIssueDeadLetter> findRecent(int limit) {
        return jpaRepository.findTop100ByOrderByReceivedAtDescIdDesc().stream()
            .limit(Math.min(limit, RECENT_LIMIT))
            .map(CouponIssueDeadLetterEntity::toDomain)
            .toList();
    }

    @Override
    public List<CouponIssueDeadLetter> findByIds(List<Long> ids) {
        return jpaRepository.findAllById(ids).stream().map(CouponIssueDeadLetterEntity::toDomain).toList();
    }

    @Override
    public void markReplayed(Long id, Instant replayedAt) {
        jpaRepository.markReplayed(id, replayedAt, CouponIssueDeadLetter.Status.PENDING,
            CouponIssueDeadLetter.Status.REPLAYED);
    }
}
