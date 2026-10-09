package com.loopers.user.infrastructure;

import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.PointRepository;
import com.loopers.user.domain.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository repository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PointRepository pointRepository;

    @DisplayName("[R-POINT-06] 충전한 포인트 잔액은 DB에 저장된다.")
    @Nested
    class PersistChargedBalance {
        @DisplayName("[상태 전이] 10000 충전 후 flush·clear하여 조회하면 잔액이 10000이다.")
        @Test
        void reloadsChargedBalance() {
            User user = repository.save(new User());
            pointRepository.save(new Point(user.getId(), 10_000L));
            entityManager.flush();
            entityManager.clear();

            Point result = pointRepository.findByUserId(user.getId()).orElseThrow();

            assertThat(result.balance()).isEqualTo(10_000L);
        }
    }
}
