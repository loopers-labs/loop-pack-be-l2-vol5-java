package com.loopers;

import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브랜드 삭제 흐름. W5부터 "과거 주문"은 PG 결제로 확정한 주문이다 — 로컬 테스트 PG fixture로 접수 → 성공 → reconcile 반영까지 HTTP로 만든다.
 */
// W5: PG fixture 주소(또는 발송 mock) 때문에 이 클래스만 쓰는 컨텍스트가 생긴다. 다른 클래스가 재사용하지 않으므로 끝나면 닫아
// DB 커넥션을 돌려준다 — 캐시된 컨텍스트마다 Hikari 풀을 잡아 로컬 MySQL max_connections(151)를 넘기지 않게.
@DirtiesContext
@SpringBootTest
@AutoConfigureMockMvc
public class BrandDeleteFlowE2ETest {

    private static final String USER_HEADER = "X-USER-ID";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final LocalTestPgServer PG = LocalTestPgServer.start();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        // W4: 목록이 캐시되므로, DB만 비우면 다음 테스트가 이전 테스트의 목록을 받는다
        redisCleanUp.truncateAll();
    }


    // W5: 과거 주문은 PG 성공으로 확정한다. 포인트는 쓰지 않으므로(C-1) 잔액 기대값이 4,000원(10,000 − 6,000)에서 10,000원으로 바뀐다.
    @DisplayName("브랜드 삭제 → 브랜드·연결 상품(재고 0 포함) 삭제, 다른 브랜드 상품·과거 주문 유지, 삭제 상품의 고객·관리자 사용 제한, 기존 좋아요 취소는 성공, 삭제 전 DRAFT 확정은 409")
    @Test
    void deletesBrandWithItsProducts_andKeepsOthersAndPastOrders() throws Exception {
        // given
        PG.reset();
        UserModel buyer = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        BrandModel adidas = brandJpaRepository.save(new BrandModel("아디다스", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 3_000, 5));
        ProductModel soldOut = productJpaRepository.save(new ProductModel(nike.getId(), "품절 상품", 1_000, 0));
        ProductModel superstar = productJpaRepository.save(new ProductModel(adidas.getId(), "슈퍼스타", 2_000, 7));

        chargePoints(buyer, 10_000);

        Long pastOrderId = createOrder(buyer, airMax, 2);
        Long draftOrderId = createOrder(buyer, airMax, 1);

        confirmByPg(buyer, pastOrderId, "past-attempt");
        mockMvc.perform(post("/api/v1/products/" + airMax.getId() + "/likes").header(USER_HEADER, buyer.getId())).andExpect(status().isOk());


        // when
        mockMvc.perform(delete("/api-admin/v1/brands/" + nike.getId()).with(ADMIN).with(csrf())).andExpect(status().isOk());


        // then 1 : 브랜드와 재고 0을 포함한 연결 상품만 삭제
        assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(productJpaRepository.findById(soldOut.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(brandJpaRepository.findById(adidas.getId()).orElseThrow().getDeletedAt()).isNull();
        ProductModel untouched = productJpaRepository.findById(superstar.getId()).orElseThrow();
        assertThat(untouched.getDeletedAt()).isNull();
        assertThat(untouched.getStock()).isEqualTo(7);


        // then 2 : 과거 주문은 주문 당시의 이름·단가·수량·총액·결제액 그대로 조회
        mockMvc.perform(get("/api/v1/orders/" + pastOrderId).header(USER_HEADER, buyer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
                .andExpect(jsonPath("$.data.items[0].productName").value("에어맥스"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(3_000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(6_000))
                .andExpect(jsonPath("$.data.paidAmount").value(6_000));


        // then 3 : 고객 목록·상세·새 좋아요·내 좋아요 목록·새 주문서 작성에서 빠짐
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(superstar.getId()));
        mockMvc.perform(get("/api/v1/products/" + airMax.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/products/" + soldOut.getId() + "/likes").header(USER_HEADER, buyer.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/" + buyer.getId() + "/likes").header(USER_HEADER, buyer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, buyer.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"productId\": " + airMax.getId() + ", \"quantity\": 1}]}"))
                .andExpect(status().isNotFound());


        // then 4 : 기존 좋아요 취소는 그대로 성공
        mockMvc.perform(delete("/api/v1/products/" + airMax.getId() + "/likes").header(USER_HEADER, buyer.getId())).andExpect(status().isOk());


        // then 5 : 관리자 수정, 재고 변경은 거절
        mockMvc.perform(put("/api-admin/v1/products/" + airMax.getId()).with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"에어맥스2\", \"price\": 4000}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api-admin/v1/products/" + airMax.getId() + "/stock").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\": 100}"))
                .andExpect(status().isNotFound());


        // then 6 : 삭제 전에 만든 주문서 Draft는 확정 불가, 재고/잔액/주문 상태는 바뀌지 않는다.
        mockMvc.perform(confirm(buyer, draftOrderId, "draft-attempt"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Conflict"));
        mockMvc.perform(get("/api/v1/orders/" + draftOrderId).header(USER_HEADER, buyer.getId()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, buyer.getId()))
                .andExpect(jsonPath("$.data.balance").value(10_000));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(3);
        assertThat(paymentJpaRepository.count()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder confirm(UserModel user, Long orderId, String key) {
        return post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, user.getId())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardType\": \"SAMSUNG\", \"cardNo\": \"1234-5678-9814-1451\"}");
    }

    /** 확정 요청(202) → PG 거래 PENDING → fixture에서 SUCCESS → reconcile로 CONFIRMED (W5 plan.md 4·8). */
    private void confirmByPg(UserModel user, Long orderId, String key) throws Exception {
        mockMvc.perform(confirm(user, orderId, key)).andExpect(status().isAccepted());
        await().atMost(WAIT).until(() -> paymentJpaRepository.findByOrderId(orderId).orElseThrow().getStatus() == PaymentStatus.PENDING);
        PG.complete(paymentJpaRepository.findByOrderId(orderId).orElseThrow().getTransactionKey(), "SUCCESS", null);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payment/reconcile").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
    }


    private void chargePoints(UserModel user, int amount) throws Exception {
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amount + "}"))
                .andExpect(status().isOk());
    }

    private Long createOrder(UserModel user, ProductModel product, int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": " + quantity + "}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("$.data.id", Long.class);
    }
}
