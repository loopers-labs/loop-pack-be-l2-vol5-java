package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointTransaction;
import com.loopers.domain.point.UserPoint;
import com.loopers.domain.point.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserPointRepositoryImpl implements UserPointRepository {

    private final UserPointJpaRepository userPointJpaRepository;
    private final PointTransactionJpaRepository pointTransactionJpaRepository;

    @Override
    public UserPoint loadForUpdate(Long userId) {
        userPointJpaRepository.lockOrOpen(userId);
        return userPointJpaRepository.findByUserIdForUpdate(userId)
            .map(UserPointEntity::toDomain)
            .orElseThrow(() -> new IllegalStateException("행을 연 직후인데 찾을 수 없습니다: userId=" + userId));
    }

    @Override
    public Optional<UserPoint> findForUpdate(Long userId) {
        return userPointJpaRepository.findByUserIdForUpdate(userId).map(UserPointEntity::toDomain);
    }

    @Override
    public Optional<UserPoint> findByUserId(Long userId) {
        return userPointJpaRepository.findByUserId(userId).map(UserPointEntity::toDomain);
    }

    @Override
    public UserPoint save(UserPoint userPoint) {
        Long userId = userPoint.getUserId();
        UserPointEntity entity = userPointJpaRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> userPointJpaRepository.save(UserPointEntity.open(userId)));
        entity.changeBalance(userPoint.getBalance().amount());

        userPoint.pullNewTransactions()
            .forEach(transaction -> pointTransactionJpaRepository.save(PointTransactionEntity.from(userId, transaction)));

        return entity.toDomain();
    }

    @Override
    public List<PointTransaction> findTransactions(Long userId) {
        return pointTransactionJpaRepository.findByUserIdOrderByIdAsc(userId).stream()
            .map(PointTransactionEntity::toDomain)
            .toList();
    }
}
