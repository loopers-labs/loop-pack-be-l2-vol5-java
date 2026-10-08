package com.loopers.application.point;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.point.fixture.PointFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.support.ConcurrentRequests;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.Callable;

@SpringBootTest
class PointConcurrencyTest {
    @Autowired private ChargePointFacade chargePoint;
    @Autowired private UserFixture users;
    @Autowired private PointFixture points;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 첫_충전을_동시에_요청하면_한_잔액_행에_두_충전액이_모두_반영된다() throws Exception {
        // arrange
        users.createUser(1);
        List<Callable<Long>> requests =
                List.of(() -> chargePoint.charge(1L, 1_000L), () -> chargePoint.charge(1L, 2_000L));

        // act
        var result = ConcurrentRequests.run(requests);

        // assert
        assertThat(result.successes()).hasSize(2);
        assertThat(result.failures()).isEmpty();
        assertThat(points.rowCount(1)).isEqualTo(1);
        assertThat(points.balance(1)).isEqualTo(3_000);
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
