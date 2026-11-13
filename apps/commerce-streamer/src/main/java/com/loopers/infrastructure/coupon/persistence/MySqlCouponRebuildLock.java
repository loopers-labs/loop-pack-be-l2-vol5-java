package com.loopers.infrastructure.coupon.persistence;

import com.loopers.domain.coupon.repository.CouponRebuildLock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** MySQL 이름 잠금. 잠금은 연결에 묶이므로 잡은 연결을 task 가 끝날 때까지 쥐고 같은 연결로 푼다 (8주차 D-174) */
@RequiredArgsConstructor
@Component
public class MySqlCouponRebuildLock implements CouponRebuildLock {

    private final JdbcTemplate jdbc;

    @Override
    public boolean runExclusively(long campaignId, Runnable task) {
        String name = "coupon-rebuild-" + campaignId;
        Boolean ran = jdbc.execute((Connection connection) -> {
            if (!acquire(connection, name)) {
                return false;
            }
            try {
                task.run();
                return true;
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, name);
                    release.execute();
                }
            }
        });
        return Boolean.TRUE.equals(ran);
    }

    private static boolean acquire(Connection connection, String name) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            lock.setString(1, name);
            try (ResultSet rs = lock.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }
}
