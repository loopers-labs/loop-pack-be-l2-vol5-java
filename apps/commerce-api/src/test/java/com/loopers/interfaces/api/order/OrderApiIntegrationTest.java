package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderNotFoundException;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private PointApplicationService points;
    @Autowired private OrderApplicationService orders;
    @Autowired private UserJpaRepository users;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private long first;
    private long second;
    @BeforeEach
    void prepare() {
        users.save(new UserJpaEntity(1));
        users.save(new UserJpaEntity(2));
        long brand = brands.create("브랜드").id().value();
        first = products.create(brand, "첫 상품", 2000, 5).id();
        second = products.create(brand, "둘째 상품", 3000, 5).id();
    }
    @AfterEach void clean() { jdbc.update("delete from order_items"); cleanup.truncateAllTables(); }
    private List<OrderApplicationService.ItemRequest> items() {
        return List.of(new OrderApplicationService.ItemRequest(first, 2), new OrderApplicationService.ItemRequest(second, 1));
    }

    @Test
    @DisplayName("서로 다른 주문이 마지막 재고를 동시에 확정하면 하나만 성공한다")
    void preventsOversellingAcrossOrders() throws Exception {
        products.setStock(first, 1);
        points.charge(1, 10000);
        points.charge(2, 10000);
        var a = orders.create(1, List.of(new OrderApplicationService.ItemRequest(first, 1)));
        var b = orders.create(2, List.of(new OrderApplicationService.ItemRequest(first, 1)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var taskA = pool.submit(() -> { latch.await(); return attemptConfirm(1, a.id()); });
            var taskB = pool.submit(() -> { latch.await(); return attemptConfirm(2, b.id()); });
            latch.countDown();
            assertThat(List.of(taskA.get(20, TimeUnit.SECONDS), taskB.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(products.getAdminProduct(first).stock()).isZero();
        assertThat(points.balance(1) + points.balance(2)).isEqualTo(18000);
    }

    private boolean attemptConfirm(long userId, long orderId) {
        try { orders.confirm(userId, orderId); return true; }
        catch (RuleViolationException e) { return false; }
    }

    @Test
    @DisplayName("충전 API에서 여러 품목 주문 확정과 내 주문·잔액 및 관리자 조회까지 연결된다")
    void completesHttpFlow() throws Exception {
        mvc.perform(post("/api/v1/points/charge").header("X-USER-ID", "1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":10000}")).andExpect(status().isOk());
        var response = mvc.perform(post("/api/v1/orders").header("X-USER-ID", "1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[{\"productId\":" + first + ",\"quantity\":2},{\"productId\":" + second + ",\"quantity\":1}]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.total").value(7000)).andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertThat(points.balance(1)).isEqualTo(10000);
        assertThat(products.getAdminProduct(first).stock()).isEqualTo(5);
        products.change(first, "가격 변경", 9999);
        mvc.perform(post("/api/v1/orders/{id}/confirm", id).header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.paidAmount").value(7000))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"));
        mvc.perform(get("/api/v1/orders/{id}", id).header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/points").header("X-USER-ID", "1")).andExpect(jsonPath("$.data.balance").value(3000));
        mvc.perform(get("/api/v1/orders").header("X-USER-ID", "2")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api-admin/v1/orders").param("userId", "1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id));
        mvc.perform(get("/api-admin/v1/orders/{id}", id).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/{id}/confirm", id).header("X-USER-ID", "1")).andExpect(status().isConflict());
        assertThat(products.getAdminProduct(first).stock()).isEqualTo(3);
        assertThat(points.balance(1)).isEqualTo(3000);
    }

    @Test
    @DisplayName("잔액 부족으로 확정이 실패하면 먼저 차감한 모든 상품 재고와 주문 상태가 롤백된다")
    void rollsBackAfterStockChanges() {
        var order = orders.create(1, items());
        points.charge(1, 100);
        assertThatThrownBy(() -> orders.confirm(1, order.id())).isInstanceOf(RuleViolationException.class);
        assertThat(products.getAdminProduct(first).stock()).isEqualTo(5);
        assertThat(products.getAdminProduct(second).stock()).isEqualTo(5);
        assertThat(points.balance(1)).isEqualTo(100);
        assertThat(orders.getMyOrder(1, order.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("뒤 품목의 재고 부족과 타인 확정 요청은 재고·잔액·주문을 변경하지 않는다")
    void rejectsStockShortageAndOtherUser() {
        var order = orders.create(1, items());
        points.charge(1, 10000);
        products.setStock(second, 0);
        assertThatThrownBy(() -> orders.confirm(2, order.id())).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orders.confirm(1, order.id())).isInstanceOf(RuleViolationException.class);
        assertThat(products.getAdminProduct(first).stock()).isEqualTo(5);
        assertThat(points.balance(1)).isEqualTo(10000);
        assertThat(orders.getMyOrder(1, order.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("같은 주문의 동시 확정은 한 번만 성공하고 재고와 포인트를 한 번만 차감한다")
    void confirmsOnceConcurrently() throws Exception {
        var order = orders.create(1, items());
        points.charge(1, 20000);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> task = () -> {
                try { orders.confirm(1, order.id()); return true; }
                catch (RuleViolationException e) { return false; }
            };
            var a = pool.submit(task);
            var b = pool.submit(task);
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(points.balance(1)).isEqualTo(13000);
        assertThat(products.getAdminProduct(first).stock()).isEqualTo(3);
    }

    @Test
    @DisplayName("빈 주문·중복 품목·0 수량은 400으로 거절하고 주문을 저장하지 않는다")
    void rejectsInvalidOrders() throws Exception {
        for (String body : List.of("{\"items\":[]}",
            "{\"items\":[{\"productId\":" + first + ",\"quantity\":0}]}",
            "{\"items\":[{\"productId\":" + first + ",\"quantity\":1},{\"productId\":" + first + ",\"quantity\":1}]}")) {
            mvc.perform(post("/api/v1/orders").header("X-USER-ID", "1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(orders.listMyOrders(1, 0, 20)).isEmpty();
    }
}
