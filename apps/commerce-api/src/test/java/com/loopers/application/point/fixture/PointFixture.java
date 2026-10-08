package com.loopers.application.point.fixture;

import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class PointFixture {
    @Autowired private PointBalanceRepository points;
    @Autowired private PointBalanceJpaRepository pointRows;
    @PersistenceContext private EntityManager entityManager;

    public PointBalance createBalance(long userId, long amount) {
        PointBalance point = PointBalance.empty(userId);
        if (amount != 0) {
            point.charge(amount);
        }
        return points.save(point);
    }

    @Transactional(readOnly = true)
    public long balance(long userId) {
        return points.findByUserId(userId).orElseThrow().getBalance();
    }

    @Transactional(readOnly = true)
    public long rowCount(long userId) {
        return entityManager
                .createQuery(
                        "select count(p) from PointBalanceJpaEntity p where p.userId = :userId",
                        Long.class)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    @Transactional(readOnly = true)
    public long rowCount() {
        return pointRows.count();
    }
}
