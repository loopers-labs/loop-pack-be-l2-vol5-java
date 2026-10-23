package com.loopers.domain.order;

import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class StockReservationRepositoryTest {

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("저장한 예약은 flush/clear 후에도 주문 id로 찾고, 소비·반환한 상태가 그대로 남는다.")
    @Test
    void findsByOrderId_withPersistedStatus() {
        // arrange
        StockReservationModel consumed = stockReservationRepository.save(new StockReservationModel(1L));
        StockReservationModel released = stockReservationRepository.save(new StockReservationModel(2L));
        stockReservationRepository.save(new StockReservationModel(3L));
        consumed.consume();
        released.release();
        entityManager.flush();
        entityManager.clear();

        // act & assert
        assertThat(stockReservationRepository.findByOrderId(1L).map(StockReservationModel::getStatus)).contains(ReservationStatus.CONSUMED);
        assertThat(stockReservationRepository.findByOrderId(2L).map(StockReservationModel::getStatus)).contains(ReservationStatus.RELEASED);
        assertThat(stockReservationRepository.findByOrderId(3L).map(StockReservationModel::getStatus)).contains(ReservationStatus.RESERVED);
        assertThat(stockReservationRepository.findByOrderId(99L)).isEmpty();
    }

    @DisplayName("잠금 조회도 같은 예약을 돌려준다.")
    @Test
    void findsByOrderIdForUpdate() {
        // arrange
        stockReservationRepository.save(new StockReservationModel(1L));
        entityManager.flush();
        entityManager.clear();

        // act
        Optional<StockReservationModel> locked = stockReservationRepository.findByOrderIdForUpdate(1L);

        // assert
        assertThat(locked).isPresent();
        assertThat(locked.get().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(stockReservationRepository.findByOrderIdForUpdate(99L)).isEmpty();
    }

    @DisplayName("DB 유일 제약이, 한 주문에 두 번째 예약을 저장하지 못하게 한다.")
    @Test
    void rejectsSecondReservationOfSameOrder() {
        // arrange
        stockReservationRepository.save(new StockReservationModel(1L));
        entityManager.flush();

        // act & assert: 제약 위반 뒤의 세션에는 저장에 실패한 엔티티가 남아 있어, 정리의 flush가 깨지지 않게 비운다
        assertThatThrownBy(() -> {
            stockReservationRepository.save(new StockReservationModel(1L));
            entityManager.flush();
        }).hasStackTraceContaining("uk_stock_reservation_order");
        entityManager.clear();
    }
}
