package com.loopers.infrastructure.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PointRepositoryIntegrationTest {

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("저장한 잔액이 사용자 식별자로 다시 읽힌다.")
    @Test
    void readsSavedBalance() {
        // arrange
        Point point = new Point(1L);
        point.charge(10_000L);
        pointRepository.save(point);

        // act & assert
        assertThat(pointRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L);
    }

    @DisplayName("사용자 한 명에 Point 는 하나다. 같은 사용자의 행을 두 번 만들면 유니크 키가 막는다.")
    @Test
    void rejectsSecondRowForSameUser() {
        // arrange
        pointRepository.save(new Point(1L));

        // act & assert
        assertThatThrownBy(() -> pointRepository.save(new Point(1L)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
