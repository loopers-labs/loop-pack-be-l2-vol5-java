package com.loopers.infrastructure.persistence.coupon.support;

import com.loopers.domain.coupon.exception.CouponIssueLockBusyException;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/** 업무 쓰기는 JPA에 두고, 활성 TX의 같은 연결에서 마지막 잠금의 세션 설정만 잠시 바꾼다. */
@Component
@RequiredArgsConstructor
public class JdbcCouponCampaignIssueLock {
    private final EntityManager entityManager;

    public <T> T withShortWait(Supplier<T> lock) {
        try {
            return entityManager.unwrap(Session.class).doReturningWork(connection -> {
                int previous = readTimeout(connection);
                setTimeout(connection, 1);
                RuntimeException primary = null;
                try {
                    return lock.get();
                } catch (RuntimeException failure) {
                    primary = failure;
                    throw failure;
                } finally {
                    try {
                        setTimeout(connection, previous);
                    } catch (SQLException restoreFailure) {
                        if (primary != null) {
                            primary.addSuppressed(restoreFailure);
                        } else {
                            throw restoreFailure;
                        }
                    }
                }
            });
        } catch (RuntimeException failure) {
            if (CouponSqlFailures.isLockWaitTimeout(failure)) {
                throw new CouponIssueLockBusyException(failure);
            }
            throw failure;
        }
    }

    private static int readTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
            if (!result.next()) {
                throw new SQLException("현재 DB 잠금 대기 설정을 읽을 수 없습니다.");
            }
            return result.getInt(1);
        }
    }

    private static void setTimeout(Connection connection, int seconds) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = " + seconds);
        }
    }
}
