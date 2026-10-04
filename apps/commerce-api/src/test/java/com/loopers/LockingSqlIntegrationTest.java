package com.loopers;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.brand.domain.BrandModel;
import com.loopers.common.domain.Money;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.point.adapter.out.persistence.PointGroupJpaRepository;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.point.application.port.in.PointExpirationUseCase;
import com.loopers.point.domain.PointGroup;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.ProductCommandUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.persistence.SqlRecorder;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Period;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.loopers.support.persistence.SqlRecorder")
public class LockingSqlIntegrationTest {

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @Autowired
    private PointExpirationUseCase pointExpirationUseCase;

    @Autowired
    private ProductCommandUseCase productCommandUseCase;

    @Autowired
    private BrandCommandUseCase brandCommandUseCase;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointGroupJpaRepository pointGroupJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private BrandModel nike;
    private ProductModel airMax;
    private ProductModel airForce;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("고객"));
        nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        SqlRecorder.clear();
    }

    @DisplayName("C-5·ADR-W3-03 주문 확정은 주문 → 상품 → 포인트 그룹 순서로 잠근다 (재고·잔액·같은 주문 중복 확정 보호)")
    @Test
    void confirmLocksOrderThenProductsThenPointGroups() {
        // arrange
        pointCommandUseCase.charge(user.getId(), 10_000);
        Long orderId = orderCommandUseCase.create(user.getId(), List.of(
                new OrderLines.Line(airForce.getId(), 1),
                new OrderLines.Line(airMax.getId(), 1)
        )).id();
        SqlRecorder.clear();

        // act
        orderCommandUseCase.confirm(user.getId(), orderId);

        // assert
        assertThat(SqlRecorder.lockedTables()).containsExactly("orders", "products", "point_groups");
    }

    @DisplayName("C-5 관리자 상품 수정·재고 설정·삭제도 상품 행을 잠근다 (확정의 재고 차감을 덮어쓰지 않음)")
    @Test
    void adminProductChangesLockProductRow() {
        // act
        SqlRecorder.clear();
        productCommandUseCase.update(airMax.getId(), "에어맥스2", 1_500);
        productCommandUseCase.changeStock(airMax.getId(), 3);
        productCommandUseCase.delete(airMax.getId());

        // assert
        assertThat(SqlRecorder.lockedTables()).containsExactly("products", "products", "products");
    }

    @DisplayName("C-5 브랜드 수정과 상품 등록도 브랜드 행을 잠근다 (일괄 삭제를 되살리거나, 삭제된 브랜드에 상품을 남기지 않음)")
    @Test
    void brandUpdateAndProductCreateLockBrandRow() {
        // act
        SqlRecorder.clear();
        brandCommandUseCase.update(nike.getId(), "나이키2", null);
        productCommandUseCase.create(nike.getId(), "에어조던", 3_000, 5);

        // assert
        assertThat(SqlRecorder.lockedTables()).containsExactly("brands", "brands");
    }

    @DisplayName("C-5 브랜드 일괄 삭제는 브랜드 → 상품 순서로 잠근다")
    @Test
    void brandDeleteLocksBrandThenProducts() {
        // act
        SqlRecorder.clear();
        brandCommandUseCase.delete(nike.getId());

        // assert
        assertThat(SqlRecorder.lockedTables()).containsExactly("brands", "products");
    }

    @DisplayName("C-5 포인트 만료 배치도 그룹을 잠근다 (확정의 결제와 같은 그룹을 덮어쓰지 않음)")
    @Test
    void expirationLocksPointGroups() {
        // arrange
        ZonedDateTime chargedAt = ZonedDateTime.now().minusYears(2);
        pointGroupJpaRepository.save(PointGroup.charge(user.getId(), Money.of(1_000), chargedAt, Period.ofYears(1)));
        SqlRecorder.clear();

        // act
        int expired = pointExpirationUseCase.expireAll(ZonedDateTime.now());

        // assert
        assertThat(expired).isEqualTo(1);
        assertThat(SqlRecorder.lockedTables()).first().isEqualTo("point_groups");
    }

}
