package com.loopers.interfaces.api;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderTransactionTest {

    private static final String USER_ID = "1";
    private static final long INITIAL_BALANCE = 100_000L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    private ProductModel runner;
    private ProductModel slipper;
    private Long draftOrderId;
    private OrderAdminInfo draftBefore;

    @BeforeEach
    void setUp() {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        runner = productJpaRepository.save(new ProductModel(brand.getId(), "runner", 10_000L, 5));
        slipper = productJpaRepository.save(new ProductModel(brand.getId(), "slipper", 5_000L, 3));

        PointModel point = new PointModel(Long.parseLong(USER_ID));
        point.charge(INITIAL_BALANCE);
        pointJpaRepository.save(point);

        // runner 2개 + slipper 1개 = 25,000원
        OrderModel draft = new OrderModel(Long.parseLong(USER_ID), List.of(
            new OrderItem(runner.getId(), 2, 10_000L),
            new OrderItem(slipper.getId(), 1, 5_000L)
        ));
        draftOrderId = orderJpaRepository.save(draft).getId();
        draftBefore = orderFacade.getOrderForAdmin(draftOrderId);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("여러 품목의 주문을 확정하면, 재고·잔액·주문 상태와 결제액이 모두 반영된다.")
    @Test
    void confirmsAllItems_andReflectsStockBalanceAndOrder() throws Exception {
        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm").header("X-USER-ID", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paidAmount").value(25_000));

        // assert
        assertThat(stockOf(runner)).isEqualTo(3);
        assertThat(stockOf(slipper)).isEqualTo(2);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 25_000L);
        OrderAdminInfo after = orderFacade.getOrderForAdmin(draftOrderId);
        assertThat(after.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(after.paidAmount()).isEqualTo(25_000L);
        assertThat(after.items()).isEqualTo(draftBefore.items());
    }

    @DisplayName("재고·포인트 변경이 DB로 나간 뒤 주문 저장에서 실패하면, 주문은 DRAFT로 남고 재고와 잔액도 원래대로다.")
    @Test
    void rollsBackEverything_whenOrderSaveFailsAfterChangesAreFlushed() throws Exception {
        // arrange: 주문을 저장하기 직전에 앞선 변경을 flush하고 예외를 던진다
        AtomicBoolean failureInjected = new AtomicBoolean(false);
        AtomicBoolean hadPendingChanges = new AtomicBoolean(false);
        doAnswer(invocation -> {
            failureInjected.set(true);
            hadPendingChanges.set(entityManager.unwrap(Session.class).isDirty());
            entityManager.flush();
            throw new IllegalStateException("주문 저장 실패");
        }).when(orderRepository).save(any(OrderModel.class));

        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm").header("X-USER-ID", USER_ID))
            .andExpect(status().isInternalServerError());

        // assert: 실패 시점에 재고·포인트 변경이 실제로 나갔던 것을 확인하고
        assertThat(failureInjected).isTrue();
        assertThat(hadPendingChanges).isTrue();

        // assert: 요청이 끝난 뒤 새로 조회한 값이 변경 전과 같다
        assertThat(stockOf(runner)).isEqualTo(5);
        assertThat(stockOf(slipper)).isEqualTo(3);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        OrderAdminInfo after = orderFacade.getOrderForAdmin(draftOrderId);
        assertThat(after.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(after.paidAmount()).isNull();
        assertThat(after).isEqualTo(draftBefore);
    }

    @DisplayName("주문을 만든 뒤 상품이 삭제되면, 확정 시 404를 응답하고 주문·재고·잔액은 그대로다.")
    @Test
    void returns404_andKeepsEverything_whenProductIsDeletedAfterDraft() throws Exception {
        // arrange
        mvc.perform(delete("/api-admin/v1/products/" + slipper.getId())
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk());

        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm").header("X-USER-ID", USER_ID))
            .andExpect(status().isNotFound());

        // assert
        assertThat(stockOf(runner)).isEqualTo(5);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(orderFacade.getOrderForAdmin(draftOrderId)).isEqualTo(draftBefore);
    }

    @DisplayName("이미 확정된 주문을 다시 확정하면, 404를 응답하고 재고와 잔액은 한 번만 반영된 상태다.")
    @Test
    void returns404_andAppliesOnce_whenConfirmedAgain() throws Exception {
        // arrange
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm").header("X-USER-ID", USER_ID))
            .andExpect(status().isOk());

        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm").header("X-USER-ID", USER_ID))
            .andExpect(status().isNotFound());

        // assert
        assertThat(stockOf(runner)).isEqualTo(3);
        assertThat(stockOf(slipper)).isEqualTo(2);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 25_000L);
    }

    private int stockOf(ProductModel target) {
        return productJpaRepository.findById(target.getId()).orElseThrow().getStock();
    }

    private long balance() {
        return pointJpaRepository.findByUserId(Long.parseLong(USER_ID)).orElseThrow().getBalance();
    }
}
