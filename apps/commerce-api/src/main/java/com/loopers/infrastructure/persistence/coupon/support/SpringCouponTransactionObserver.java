package com.loopers.infrastructure.persistence.coupon.support;

import com.loopers.application.coupon.port.CouponTransactionObserver;
import com.loopers.application.coupon.support.CouponExecution;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpringCouponTransactionObserver implements CouponTransactionObserver {
    private final EntityManager entityManager;

    @Override
    public void observe(CouponExecution execution) {
        if (execution == null) {
            return;
        }
        int index = execution.started();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Coupon observation requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                CouponExecution.Completion completion = switch (status) {
                    case STATUS_COMMITTED -> CouponExecution.Completion.COMMITTED;
                    case STATUS_ROLLED_BACK -> CouponExecution.Completion.ROLLED_BACK;
                    default -> CouponExecution.Completion.UNKNOWN;
                };
                execution.completed(index, completion);
                log.info("coupon execution={} tx={} stage=TX_COMPLETED outcome={}", execution.id(), index, completion);
            }
        });
        CouponExecution.DbWork work = entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT @@server_uuid, CONNECTION_ID()")) {
                if (!result.next()) {
                    throw new IllegalStateException("Missing coupon DB identity");
                }
                return new CouponExecution.DbWork(result.getString(1), result.getLong(2));
            }
        });
        execution.identify(index, work);
        log.info("coupon execution={} tx={} stage=TX_STARTED database={}", execution.id(), index, work);
    }
}
