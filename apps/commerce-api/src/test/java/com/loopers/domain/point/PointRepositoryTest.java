package com.loopers.domain.point;

import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PointRepositoryTest {

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("사용자id로 포인트 계정을 조회할 때, ")
    @Nested
    class FindByUserId {
        @DisplayName("저장된 계정은, flush/clear 후 재조회해도 동일한 값으로 조회된다.")
        @Test
        void returnsPoint_afterFlushAndClear() {
            // arrange
            PointModel point = new PointModel(1L);
            point.charge(1_000L);
            pointRepository.save(point);
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<PointModel> result = pointRepository.findByUserId(1L);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().getBalance()).isEqualTo(1_000L);
        }

        @DisplayName("존재하지 않는 사용자로 조회하면, 빈 결과를 반환한다.")
        @Test
        void returnsEmpty_whenUserDoesNotExist() {
            // act
            Optional<PointModel> result = pointRepository.findByUserId(999L);

            // assert
            assertThat(result).isEmpty();
        }
    }
}
