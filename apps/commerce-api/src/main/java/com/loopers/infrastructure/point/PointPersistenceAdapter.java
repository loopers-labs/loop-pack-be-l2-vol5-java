package com.loopers.infrastructure.point;

import com.loopers.application.point.port.PointRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.point.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class PointPersistenceAdapter implements PointRepository {
    private final JdbcTemplate jdbcTemplate;

    public PointPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Point findOrCreateForUpdate(long userId) {
        // 최초 행 생성 경쟁은 user_id 유일 키의 upsert로 막고, 이후 같은 사용자의 충전·결제는 행 잠금으로 직렬화한다.
        jdbcTemplate.update("insert into points(user_id,balance) values (?,0) on duplicate key update user_id=user_id", userId);
        return jdbcTemplate.queryForObject("select balance from points where user_id=? for update",
            (resultSet, rowNumber) -> new Point(userId, new Money(resultSet.getLong(1))), userId);
    }

    @Override
    public void save(Point point) {
        int updated = jdbcTemplate.update("update points set balance=? where user_id=?",
            point.getBalance().value(), point.getUserId());
        if (updated != 1) {
            throw new IllegalStateException("저장할 포인트가 없습니다.");
        }
    }
}
