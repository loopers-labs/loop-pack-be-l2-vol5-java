package com.loopers.application.ranking.service;

import com.loopers.domain.ranking.model.vo.WeeklyPurchaseScore;
import com.loopers.domain.ranking.repository.WeeklyScoreRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CalculateWeeklyScoreService {
    private final WeeklyScoreRepository repository;

    public CalculateWeeklyScoreService(WeeklyScoreRepository repository) {
        this.repository = repository;
    }

    public WeeklyPurchaseScore calculate(UUID snapshotId, long productId) {
        return repository.calculate(snapshotId, productId);
    }

    public void write(List<? extends WeeklyPurchaseScore> scores) {
        repository.upsert(scores);
    }
}
