package com.loopers.interfaces.api;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.order.StockReservationModel;
import com.loopers.domain.order.StockReservationRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

/**
 * 주문 확정(PG 결제 시작)이 로컬 TX A로 한꺼번에 반영되거나 한꺼번에 되돌아가는지 확인한다 (design.md 7-2절, 9-4절).
 * 확정은 포인트를 쓰지 않으므로 잔액은 모든 장면에서 그대로여야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestPgResetExtension.class)
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
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    private final TestPgServer pg = TestPgServer.shared();

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

    @DisplayName("여러 품목의 PG 결제를 시작하면, 품목별 재고가 확보되고 주문은 결제 대기이며 잔액은 변하지 않는다. PG가 성공하면 재고를 다시 줄이지 않고 확정된다.")
    @Test
    void reservesAllItems_thenConfirmsWithoutTouchingStockAgainOrBalance() throws Exception {
        // act: 결제 시작
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(draftOrderId)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data.reconcilePath").value("/api/v1/orders/" + draftOrderId + "/payment/reconcile"))
            .andExpect(jsonPath("$.data.orderPath").value("/api/v1/orders/" + draftOrderId));

        // assert: 시작 직후 — 품목별로 확보한 재고, 그대로인 잔액, PG 거래 1건
        assertThat(stockOf(runner)).isEqualTo(3);
        assertThat(stockOf(slipper)).isEqualTo(2);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(pg.createCount()).isEqualTo(1);
        assertThat(reservationStatus()).isEqualTo(ReservationStatus.RESERVED);

        // act: PG가 성공을 알린 뒤 조회로 확인
        PaymentTestSupport.completeAt(pg, draftOrderId, TestPgStatus.SUCCESS);
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/payment/reconcile").header("X-USER-ID", USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paidAmount").value(25_000));

        // assert: 재고는 확보한 그대로(추가 차감 없음), 예약 소비, 잔액 변화 0, 품목은 그대로
        assertThat(stockOf(runner)).isEqualTo(3);
        assertThat(stockOf(slipper)).isEqualTo(2);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(reservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        OrderAdminInfo after = orderFacade.getOrderForAdmin(draftOrderId);
        assertThat(after.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(after.paymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(after.paidAmount()).isEqualTo(25_000L);
        assertThat(after.items()).isEqualTo(draftBefore.items());
        assertThat(pg.createCount()).isEqualTo(1);
    }

    @DisplayName("재고 확보가 DB로 나간 뒤 예약 저장에서 실패하면, 주문은 DRAFT로 남고 재고도 원래대로이며 결제 시도·예약은 없고 PG는 호출되지 않는다.")
    @Test
    void rollsBackEverything_whenReservationSaveFailsAfterChangesAreFlushed() throws Exception {
        // arrange: 예약을 저장하기 직전에 앞선 변경을 flush하고 예외를 던진다
        AtomicBoolean failureInjected = new AtomicBoolean(false);
        AtomicBoolean hadPendingChanges = new AtomicBoolean(false);
        doAnswer(invocation -> {
            failureInjected.set(true);
            hadPendingChanges.set(entityManager.unwrap(Session.class).isDirty());
            entityManager.flush();
            throw new IllegalStateException("예약 저장 실패");
        }).when(stockReservationRepository).save(any(StockReservationModel.class));

        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(draftOrderId)))
            .andExpect(status().isInternalServerError());

        // assert: 실패 시점에 재고 변경이 실제로 나갔던 것을 확인하고
        assertThat(failureInjected).isTrue();
        assertThat(hadPendingChanges).isTrue();

        // assert: 요청이 끝난 뒤 새로 조회한 값이 변경 전과 같고, 발송은 일어나지 않았다
        assertThat(stockOf(runner)).isEqualTo(5);
        assertThat(stockOf(slipper)).isEqualTo(3);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(paymentAttemptJpaRepository.count()).isZero();
        assertThat(stockReservationJpaRepository.count()).isZero();
        assertThat(orderFacade.getOrderForAdmin(draftOrderId)).isEqualTo(draftBefore);
        assertThat(pg.createCount()).isZero();
    }

    @DisplayName("주문을 만든 뒤 상품이 삭제되면, 확정 시 404를 응답하고 주문·재고·잔액은 그대로이며 PG는 호출되지 않는다.")
    @Test
    void returns404_andKeepsEverything_whenProductIsDeletedAfterDraft() throws Exception {
        // arrange
        mvc.perform(delete("/api-admin/v1/products/" + slipper.getId())
                .with(user("admin").roles("ADMIN"))
                .with(csrf()))
            .andExpect(status().isOk());

        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(draftOrderId)))
            .andExpect(status().isNotFound());

        // assert
        assertThat(stockOf(runner)).isEqualTo(5);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(paymentAttemptJpaRepository.count()).isZero();
        assertThat(orderFacade.getOrderForAdmin(draftOrderId)).isEqualTo(draftBefore);
        assertThat(pg.createCount()).isZero();
    }

    @DisplayName("이미 시작한 주문을 같은 요청으로 다시 확정하면, 저장된 상태를 응답하고 재고는 한 번만 확보되며 PG는 다시 호출되지 않는다.")
    @Test
    void returnsStoredState_andReservesOnce_whenSameRequestIsRepeated() throws Exception {
        // arrange
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(draftOrderId)))
            .andExpect(status().isAccepted());

        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(draftOrderId)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

        // assert
        assertThat(stockOf(runner)).isEqualTo(3);
        assertThat(stockOf(slipper)).isEqualTo(2);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
        assertThat(stockReservationJpaRepository.count()).isEqualTo(1);
        assertThat(pg.createCount()).isEqualTo(1);
        assertThat(pg.transactionCount()).isEqualTo(1);
    }

    @DisplayName("이전에 포인트로 확정한 주문을 다시 확정하면, 기존대로 404를 응답하고 재고·잔액·PG는 건드리지 않는다.")
    @Test
    void returns404_andTouchesNothing_whenOrderWasConfirmedWithPoint() throws Exception {
        // arrange: 4주차까지의 포인트 결제 사실
        OrderModel pastOrder = new OrderModel(Long.parseLong(USER_ID), List.of(new OrderItem(runner.getId(), 1, 10_000L)));
        pastOrder.confirm(10_000L);
        Long pastOrderId = orderJpaRepository.save(pastOrder).getId();

        // act
        mvc.perform(post("/api/v1/orders/" + pastOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(pastOrderId)))
            .andExpect(status().isNotFound());

        // assert
        OrderAdminInfo after = orderFacade.getOrderForAdmin(pastOrderId);
        assertThat(after.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(after.paymentMethod()).isEqualTo(PaymentMethod.POINT);
        assertThat(after.payment()).isNull();
        assertThat(stockOf(runner)).isEqualTo(5);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(paymentAttemptJpaRepository.count()).isZero();
        assertThat(pg.createCount()).isZero();
    }

    @DisplayName("PG 결제 시도의 상태는 PENDING으로 시작해 성공 확인 뒤 SUCCESS가 된다.")
    @Test
    void attemptStatusFollowsPgObservation() throws Exception {
        // act
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/confirm")
                .header("X-USER-ID", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PaymentTestSupport.confirmBodyOf(draftOrderId)))
            .andExpect(status().isAccepted());
        PaymentAttemptStatus pending = orderFacade.getOrderForAdmin(draftOrderId).payment().status();
        PaymentTestSupport.completeAt(pg, draftOrderId, TestPgStatus.SUCCESS);
        mvc.perform(post("/api/v1/orders/" + draftOrderId + "/payment/reconcile").header("X-USER-ID", USER_ID))
            .andExpect(status().isOk());

        // assert
        assertThat(pending).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(orderFacade.getOrderForAdmin(draftOrderId).payment().status()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    private int stockOf(ProductModel target) {
        return productJpaRepository.findById(target.getId()).orElseThrow().getStock();
    }

    private long balance() {
        return pointJpaRepository.findByUserId(Long.parseLong(USER_ID)).orElseThrow().getBalance();
    }

    private ReservationStatus reservationStatus() {
        return stockReservationJpaRepository.findByOrderId(draftOrderId).orElseThrow().getStatus();
    }
}
