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
    private final JdbcTemplate jdbc;
    public PointPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public Point findOrCreateForUpdate(long userId) {
        jdbc.update("insert into points(user_id,balance) values (?,0) on duplicate key update user_id=user_id", userId);
        return jdbc.queryForObject("select balance from points where user_id=? for update",
            (rs, row) -> new Point(userId, new Money(rs.getLong(1))), userId);
    }
    @Override
    public void save(Point point) {
        int updated = jdbc.update("update points set balance=? where user_id=?", point.getBalance().value(), point.getUserId());
        if (updated != 1) { throw new IllegalStateException("저장할 포인트가 없습니다."); }
    }
}
