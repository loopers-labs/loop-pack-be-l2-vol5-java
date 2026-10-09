package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.OrderRepositoryImpl;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderConfirmationConcurrencyTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private PointFacade points;
    @Autowired private UserJpaRepository users;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private OrderRepositoryImpl persistence;

    @AfterEach
    void clean() {
        reset(persistence);
        cleanUp.truncateAllTables();
    }

    @Test
    void sameOrderIsConfirmedOnceAndSevenRequestsAreRejected() throws Exception {
        Fixture fixture = prepare();

        List<String> results = confirmTogether(fixture, 8);

        assertThat(results).filteredOn("success"::equals).hasSize(1);
        assertThat(results).filteredOn("state conflict"::equals).hasSize(7);
        verifySingleConfirmation(fixture);
    }

    @Test
    void anotherRequestCanConfirmAfterFirstSaveFailsAndRollsBack() throws Exception {
        Fixture fixture = prepare();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            if (failFirst.getAndSet(false)) {
                entityManager.flush();
                assertThat(((Number) entityManager.createNativeQuery("select quantity from product where id = :id")
                    .setParameter("id", fixture.productId()).getSingleResult()).intValue()).isEqualTo(19);
                assertThat(((Number) entityManager.createNativeQuery("select balance from point_balance where user_id = :id")
                    .setParameter("id", fixture.userId()).getSingleResult()).longValue()).isEqualTo(19_020L);
                throw new IllegalStateException("test: first confirmation failed after SQL");
            }
            return saved;
        }).when(persistence).save(any(OrderModel.class));

        List<String> results = confirmTogether(fixture, 2);

        assertThat(results).containsExactlyInAnyOrder("injected failure", "success");
        verifySingleConfirmation(fixture);
    }

    @Test
    void repeatedHttpConfirmationReturnsConflictAndOtherUserCannotConfirm() throws Exception {
        Fixture fixture = prepare();
        long otherUser = registration.register().getId();
        String path = "/api/v1/orders/" + fixture.orderId() + "/confirm";
        mvc.perform(post(path).header("X-USER-ID", otherUser)).andExpect(status().isNotFound());
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(products.findById(fixture.productId()).orElseThrow().getStockQuantity()).isEqualTo(20);
        mvc.perform(post(path).header("X-USER-ID", fixture.userId())).andExpect(status().isOk());
        mvc.perform(post(path).header("X-USER-ID", fixture.userId())).andExpect(status().isConflict());
        verifySingleConfirmation(fixture);
    }

    private Fixture prepare() {
        long userId = registration.register().getId();
        long brandId = brands.save(new BrandModel("brand", null)).getId();
        long productId = products.save(new ProductModel(brandId, "product", 1_000L, 20)).getId();
        points.charge(userId, 20_000L);
        long orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        return new Fixture(userId, productId, orderId);
    }

    private void verifySingleConfirmation(Fixture fixture) {
        transaction.executeWithoutResult(status -> {
            assertThat(products.findById(fixture.productId()).orElseThrow().getStockQuantity()).isEqualTo(19);
            assertThat(balances.findByUserId(fixture.userId()).orElseThrow().getBalance()).isEqualTo(19_020L);
            var order = orderRepository.findById(fixture.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(1_000L);
            assertThat(((Number) entityManager.createNativeQuery("select count(*) from point_usage")
                .getSingleResult()).longValue()).isEqualTo(1L);
            assertThat(((Number) entityManager.createNativeQuery("select count(*) from point_grant")
                .getSingleResult()).longValue()).isEqualTo(2L); // 최초 충전 + 한 번의 적립
        });
    }

    private List<String> confirmTogether(Fixture fixture, int count) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test start timed out");
                    }
                    try {
                        orders.confirm(fixture.userId(), fixture.orderId());
                        return "success";
                    } catch (CoreException exception) {
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
                        assertThat(exception.getMessage()).isEqualTo("확정할 수 없는 주문 상태입니다.");
                        return "state conflict";
                    } catch (IllegalStateException exception) {
                        assertThat(exception.getMessage()).isEqualTo("test: first confirmation failed after SQL");
                        return "injected failure";
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record Fixture(long userId, long productId, long orderId) {}
}
