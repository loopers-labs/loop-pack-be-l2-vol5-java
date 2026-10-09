package com.loopers.user.application;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.transaction.TransactionRetryExecutor;
import com.loopers.user.domain.User;
import com.loopers.user.domain.UserRepository;
import com.loopers.user.domain.PointRepository;
import com.loopers.user.domain.Point;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointUseCase {

    private final UserRepository userRepository;
    private final PointRepository pointRepository;
    private final TransactionRetryExecutor transactionRetryExecutor;
    private final EntityManager entityManager;

    public PointUseCase(UserRepository userRepository, PointRepository pointRepository, TransactionRetryExecutor transactionRetryExecutor, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.pointRepository = pointRepository;
        this.transactionRetryExecutor = transactionRetryExecutor;
        this.entityManager = entityManager;
    }

    public long charge(Long userId, long amount) {
        return transactionRetryExecutor.execute(() -> chargeInCurrentTransaction(userId, amount), 0);
    }

    private long chargeInCurrentTransaction(Long userId, long amount) {
        userRepository.findById(userId).orElseThrow(() -> new CoreException(ErrorCode.USER_NOT_IDENTIFIED));
        Point point = pointRepository.findByUserId(userId).orElse(new Point(userId, 0));
        point.changeBalance(point.charge(amount).balance());
        long balance = pointRepository.save(point).balance();
        entityManager.flush();
        return balance;
    }

    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        userRepository.findById(userId).orElseThrow(() -> new CoreException(ErrorCode.USER_NOT_IDENTIFIED));
        return pointRepository.findByUserId(userId).orElse(new Point(userId, 0)).balance();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new CoreException(ErrorCode.USER_NOT_IDENTIFIED));
    }
}
