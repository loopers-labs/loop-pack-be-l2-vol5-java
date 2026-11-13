package com.loopers.coupon.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST·GET /api-admin/v1/coupon-campaigns (D-7). 캠페인과 쿠폰팩을 한 트랜잭션에서 만든다 (D-5, CPN-01–06).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CouponCampaignAdminApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/coupon-campaigns";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        // @ElementCollection 표는 엔티티가 아니라 DatabaseCleanUp이 비우지 않는다
        jdbcTemplate.execute("TRUNCATE TABLE coupon_applicable_product");
    }

    private Map<String, Object> fixedCoupon() {
        Map<String, Object> coupon = new HashMap<>();
        coupon.put("name", "3천원 할인");
        coupon.put("discountType", "FIXED");
        coupon.put("discountValue", 3_000);
        coupon.put("minOrderAmount", 10_000);
        coupon.put("scope", "ALL");
        coupon.put("validityType", "DAYS_FROM_ISSUE");
        coupon.put("validDays", 7);
        return coupon;
    }

    private Map<String, Object> rateCoupon(List<Long> productIds) {
        Map<String, Object> coupon = new HashMap<>();
        coupon.put("name", "10% 할인");
        coupon.put("discountType", "RATE");
        coupon.put("discountValue", 10);
        coupon.put("maxDiscountAmount", 5_000);
        coupon.put("scope", "PRODUCTS");
        coupon.put("applicableProductIds", productIds);
        coupon.put("validityType", "FIXED_PERIOD");
        coupon.put("validFrom", "2026-11-13T00:00:00+09:00");
        coupon.put("validUntil", "2026-11-30T00:00:00+09:00");
        coupon.put("stackable", true);
        return coupon;
    }

    private Map<String, Object> campaign(List<Map<String, Object>> coupons) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "11월 선착순");
        body.put("totalQuantity", 100);
        body.put("issueStartAt", "2026-11-13T10:00:00+09:00");
        body.put("issueEndAt", "2026-11-13T11:00:00+09:00");
        body.put("coupons", coupons);
        return body;
    }

    private MvcResult postCampaign(Map<String, Object> body, int expectedStatus) throws Exception {
        return mockMvc.perform(post(ENDPOINT).with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @DisplayName("CPN-01–06 캠페인과 쿠폰 두 장(정액 전체·정률 특정 상품)을 만들고, 줄 길이 제한은 당첨자 수 × 10, 발급 수는 0이다. 조회하면 같은 내용이다.")
    @Test
    void createsCampaignWithCouponPack() throws Exception {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "에어맥스", 100_000, 10));

        JsonNode created = data(postCampaign(campaign(List.of(fixedCoupon(), rateCoupon(List.of(product.getId())))), 200));

        assertThat(created.path("totalQuantity").asInt()).isEqualTo(100);
        assertThat(created.path("queueCapacity").asInt()).isEqualTo(1_000);
        assertThat(created.path("issuedCount").asInt()).isZero();
        assertThat(created.path("coupons")).hasSize(2);
        assertThat(created.path("coupons").get(1).path("applicableProductIds").get(0).asLong()).isEqualTo(product.getId());

        JsonNode found = data(mockMvc.perform(get(ENDPOINT + "/" + created.path("id").asLong()).with(ADMIN))
            .andExpect(status().isOk()).andReturn());
        assertThat(found.path("name").asText()).isEqualTo("11월 선착순");
        assertThat(found.path("coupons").get(0).path("discountType").asText()).isEqualTo("FIXED");
        assertThat(found.path("coupons").get(1).path("maxDiscountAmount").asLong()).isEqualTo(5_000);
    }

    @DisplayName("CPN-03 쿠폰이 없으면 400이고 아무것도 저장하지 않는다.")
    @Test
    void rejectsCampaignWithoutCoupons() throws Exception {
        postCampaign(campaign(List.of()), 400);

        assertThat(count("coupon_campaign")).isZero();
    }

    @DisplayName("CPN-04 쿠폰 하나가 규칙을 어기면(정률 101%) 400이고 캠페인도 저장하지 않는다.")
    @Test
    void rollsBackWholePackWhenOneCouponInvalid() throws Exception {
        Map<String, Object> invalid = fixedCoupon();
        invalid.put("discountType", "RATE");
        invalid.put("discountValue", 101);

        postCampaign(campaign(List.of(fixedCoupon(), invalid)), 400);

        assertThat(count("coupon_campaign")).isZero();
        assertThat(count("coupon")).isZero();
    }

    @DisplayName("CPN-05 적용 상품이 없는 상품이면 404이고 아무것도 저장하지 않는다.")
    @Test
    void rejectsUnknownApplicableProduct() throws Exception {
        postCampaign(campaign(List.of(rateCoupon(List.of(999L)))), 404);

        assertThat(count("coupon_campaign")).isZero();
    }

    @DisplayName("쿠폰 목록에 빈 항목(null)이 있으면 400이다 (500이 아니라).")
    @Test
    void rejectsNullCouponItem() throws Exception {
        java.util.List<Map<String, Object>> coupons = new java.util.ArrayList<>();
        coupons.add(null);
        postCampaign(campaign(coupons), 400);

        assertThat(count("coupon_campaign")).isZero();
    }

    @DisplayName("관리자가 아니면 생성·조회·DLT 재처리 모두 403이다.")
    @Test
    void rejectsNonAdmin() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(user("customer").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(campaign(List.of(fixedCoupon())))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get(ENDPOINT + "/1")).andExpect(status().isForbidden());
        mockMvc.perform(post(ENDPOINT + "/1/dead-letters/replay").with(csrf())).andExpect(status().isForbidden());

        assertThat(count("coupon_campaign")).isZero();
    }

    @DisplayName("없는 캠페인을 조회하면 404다.")
    @Test
    void notFound() throws Exception {
        mockMvc.perform(get(ENDPOINT + "/999").with(ADMIN)).andExpect(status().isNotFound());
    }
}
