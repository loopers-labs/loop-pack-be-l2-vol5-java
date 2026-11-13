package com.loopers.domain.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponIssueDeadLetterService {

    private final CouponIssueDeadLetterRepository repository;

    public boolean record(CouponIssueDeadLetter deadLetter) {
        return repository.insertIfAbsent(deadLetter);
    }

    public List<CouponIssueDeadLetter> findRecent(int limit) {
        return repository.findRecent(limit);
    }

    public List<CouponIssueDeadLetter> findByIds(List<Long> ids) {
        return repository.findByIds(ids);
    }

    public void markReplayed(Long id, Instant replayedAt) {
        repository.markReplayed(id, replayedAt);
    }
}
