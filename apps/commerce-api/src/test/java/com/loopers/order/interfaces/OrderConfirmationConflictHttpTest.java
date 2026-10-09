package com.loopers.order.interfaces;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.infrastructure.OrderRepositoryAdapter;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.support.fixture.TestEntities;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import com.loopers.user.infrastructure.PointRepositoryAdapter;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class OrderConfirmationConflictHttpTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private PointRepositoryAdapter pointRepository;
    @MockitoSpyBean private OrderRepositoryAdapter orderRepository;

    private CommerceFixture fixture;
    private User buyer;
    private Product product;
    private Order order;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
        buyer = fixture.userWithPoint(10_000L);
        product = fixture.product(fixture.brand("Nike"), "Air", 1_000L, 5);
        order = fixture.draftOrder(buyer, fixture.item(product, 1));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("포인트 버전 충돌을 실제 DB에서 매번 일으키면 3회 시도 후 POINT_CONFLICT이며 주문 차감은 롤백된다.")
    @Test
    void reportsRealPointConflictAfterRetryExhaustion() throws Exception {
        TransactionTemplate competingTransaction = new TransactionTemplate(transactionManager);
        competingTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            competingTransaction.executeWithoutResult(status -> {
                int updated = entityManager.createNativeQuery(
                    "update point set balance = balance + 1000, version = version + 1 where user_id = :id")
                    .setParameter("id", buyer.getId()).executeUpdate();
                assertThat(updated).isEqualTo(1);
            });
            return saved;
        }).when(pointRepository).save(any(Point.class));

        confirm(HttpStatus.CONFLICT, "POINT_CONFLICT");

        verify(pointRepository, times(3)).save(any(Point.class));
        assertRolledBack(13_000L);
    }

    @DisplayName("JPA 포인트 엔티티를 포함한 충돌도 재시도 소진 시 POINT_CONFLICT로 구분한다.")
    @Test
    void reportsJpaPointConflictAfterRetryExhaustion() throws Exception {
        doAnswer(invocation -> {
            Point point = invocation.getArgument(0);
            invocation.callRealMethod();
            entityManager.flush();
            throw new OptimisticLockException("injected point conflict", null, point);
        }).when(pointRepository).save(any(Point.class));

        confirm(HttpStatus.CONFLICT, "POINT_CONFLICT");

        verify(pointRepository, times(3)).save(any(Point.class));
        assertRolledBack(10_000L);
    }

    @DisplayName("주문 충돌을 소진해도 실제 주문이 DRAFT이면 이미 확정된 주문이라고 응답하지 않는다.")
    @Test
    void doesNotMisreportDraftOrderConflictAsAlreadyConfirmed() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new ObjectOptimisticLockingFailureException(Order.class, order.getId());
        }).when(orderRepository).save(any(Order.class));

        confirm(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        verify(orderRepository, times(3)).save(any(Order.class));
        assertRolledBack(10_000L);
    }

    @DisplayName("대상을 확인할 수 없는 낙관적 충돌을 포인트 충돌이나 주문 확정으로 추측하지 않는다.")
    @Test
    void propagatesUnclassifiedConflictWithoutInventingBusinessError() throws Exception {
        doAnswer(invocation -> {
            throw new OptimisticLockingFailureException("injected unclassified conflict");
        }).when(pointRepository).save(any(Point.class));

        confirm(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        verify(pointRepository, times(3)).save(any(Point.class));
        assertRolledBack(10_000L);
    }

    private void confirm(HttpStatus status, String errorCode) throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", order.getId())
                .with(customer(buyer)).with(csrf()))
            .andExpect(failure(status, errorCode));
    }

    private void assertRolledBack(long balance) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order saved = entityManager.find(Order.class, order.getId());
            assertAll(
                () -> assertThat(saved.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(saved.getPaymentResult()).isNull(),
                () -> assertThat(TestEntities.stockQuantity(entityManager, product.getId())).isEqualTo(5),
                () -> assertThat(TestEntities.pointBalance(entityManager, buyer.getId())).isEqualTo(balance)
            );
        });
    }
}
