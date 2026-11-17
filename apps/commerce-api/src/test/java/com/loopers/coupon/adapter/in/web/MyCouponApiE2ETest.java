package com.loopers.coupon.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.adapter.out.persistence.UserCouponJpaRepository;
import com.loopers.coupon.domain.Coupon;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.domain.CouponDraft;
import com.loopers.coupon.domain.CouponScope;
import com.loopers.coupon.domain.DiscountType;
import com.loopers.coupon.domain.UserCoupon;
import com.loopers.coupon.domain.ValidityType;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/coupons (D-7). 발급 결과와 할인 규칙을 합쳐 최근 발급 순으로 보여 준다. 남의 쿠폰은 보이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyCouponApiE2ETest {

    private static final ZonedDateTime START = ZonedDateTime.parse("2026-11-13T10:00:00+09:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private UserCouponJpaRepository userCouponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("내 쿠폰만 최근 발급 순으로, 쿠폰 이름·할인·만료 시각과 함께 돌려준다.")
    @Test
    void listsOnlyMyCoupons() throws Exception {
        UserModel me = userJpaRepository.save(new UserModel("고객"));
        UserModel other = userJpaRepository.save(new UserModel("다른 고객"));
        CouponCampaign campaign = campaignJpaRepository.save(new CouponCampaign("선착순", 10, null, START, START.plusHours(1)));
        Coupon fixed = couponJpaRepository.save(Coupon.of(campaign.getId(), new CouponDraft("3천원", DiscountType.FIXED, 3_000, null, 10_000,
            CouponScope.ALL, Set.of(), ValidityType.DAYS_FROM_ISSUE, null, null, 7, false)));
        Coupon rate = couponJpaRepository.save(Coupon.of(campaign.getId(), new CouponDraft("10%", DiscountType.RATE, 10, 5_000L, 0,
            CouponScope.ALL, Set.of(), ValidityType.DAYS_FROM_ISSUE, null, null, 3, false)));
        ZonedDateTime issuedAt = START.plusMinutes(1);
        userCouponJpaRepository.save(new UserCoupon(fixed, me.getId(), issuedAt));
        userCouponJpaRepository.save(new UserCoupon(rate, me.getId(), issuedAt));
        userCouponJpaRepository.save(new UserCoupon(fixed, other.getId(), issuedAt));

        String body = mockMvc.perform(get("/api/v1/coupons").header("X-USER-ID", me.getId()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");

        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("name").asText()).isEqualTo("10%");
        assertThat(data.get(0).path("maxDiscountAmount").asLong()).isEqualTo(5_000);
        assertThat(data.get(1).path("name").asText()).isEqualTo("3천원");
        assertThat(data.get(1).path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(ZonedDateTime.parse(data.get(1).path("expiresAt").asText()).toInstant())
            .isEqualTo(issuedAt.plusDays(7).toInstant());
    }

    @DisplayName("USR-01 X-USER-ID가 없으면 401이다.")
    @Test
    void requiresUser() throws Exception {
        mockMvc.perform(get("/api/v1/coupons")).andExpect(status().isUnauthorized());
    }
}
