package com.loopers.infrastructure.user.persistence;

import com.loopers.domain.user.entity.User;
import com.loopers.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Component
public class UserRepositoryImpl implements UserRepository {
    private static final int BATCH_SIZE = 1_000;

    private final UserJpaRepository userJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public User save(User user) {
        return userJpaRepository.save(user);
    }

    @Override
    public boolean exists(Long userId) {
        return userJpaRepository.existsById(userId);
    }

    @Override
    public long count() {
        return userJpaRepository.count();
    }

    /** IDENTITY 키라 JPA 는 묶음 insert 를 하지 않아 JDBC 로 넣는다. 드라이버가 rewriteBatchedStatements 로 묶는다 */
    @Override
    public void createUsers(int count) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {now, now});
            if (rows.size() == BATCH_SIZE || i == count - 1) {
                jdbcTemplate.batchUpdate("INSERT INTO users (created_at, updated_at) VALUES (?, ?)", rows);
                rows.clear();
            }
        }
    }
}
