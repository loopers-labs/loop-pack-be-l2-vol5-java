package com.loopers.interfaces.api.coupon;

import com.loopers.domain.coupon.CouponIssueDeadLetter;
import com.loopers.domain.coupon.CouponIssueDeadLetterService;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CouponIssueDeadLetterAdminV1ApiTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String PATH = "/api-admin/v1/coupon-issues/dead-letters";

    private final MockMvc mvc;
    private final CouponIssueDeadLetterService deadLetterService;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    CouponIssueDeadLetterAdminV1ApiTest(MockMvc mvc, CouponIssueDeadLetterService deadLetterService,
                                        DatabaseCleanUp databaseCleanUp) {
        this.mvc = mvc;
        this.deadLetterService = deadLetterService;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void seed(String messageKey, Optional<CouponIssue> issue, Instant receivedAt) {
        deadLetterService.record(CouponIssueDeadLetter.received(messageKey, issue, "{}", "쿠폰 캠페인을 찾을 수 없습니다.",
            receivedAt));
    }

    private Long firstPendingId() {
        return deadLetterService.findRecent(100).get(0).id();
    }

    @DisplayName("WRK-010: 관리자는 최근 DLT 로그를 최신순으로 본다. 읽을 수 없는 메시지는 접수 정보 없이 보인다")
    @Test
    void listsRecentDeadLetters() throws Exception {
        seed("coupon-issues-0@1", Optional.of(new CouponIssue(7L, 42L, 3, NOW)), NOW);
        seed("coupon-issues-0@2", Optional.empty(), NOW.plusSeconds(1));

        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].messageKey").value("coupon-issues-0@2"))
            .andExpect(jsonPath("$.data[0].campaignId").doesNotExist())
            .andExpect(jsonPath("$.data[0].status").value("PENDING"))
            .andExpect(jsonPath("$.data[1].campaignId").value(7))
            .andExpect(jsonPath("$.data[1].userId").value(42))
            .andExpect(jsonPath("$.data[1].issueNumber").value(3))
            .andExpect(jsonPath("$.data[1].error").value("쿠폰 캠페인을 찾을 수 없습니다."));
    }

    @DisplayName("WRK-010: 관리자가 id 를 골라 다시 보내면 원본 토픽에 메시지가 가고 REPLAYED 가 된다")
    @Test
    void replaysChosenDeadLetters() throws Exception {
        seed("coupon-issues-0@1", Optional.of(new CouponIssue(7L, 42L, 3, NOW)), NOW);
        Long id = firstPendingId();

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(KafkaTestContainersConfig.COUPON_ISSUES,
            () -> {
                try {
                    mvc.perform(post(PATH + "/replay").with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ids\":[" + id + "]}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.replayed").value(1));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

        assertThat(records).singleElement().satisfies(record -> assertThat(record.key()).isEqualTo("7"));
        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$.data[0].status").value("REPLAYED"));
    }

    @DisplayName("일반 사용자는 DLT 로그를 볼 수 없다")
    @Test
    void rejectsCustomer() throws Exception {
        mvc.perform(get(PATH).with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
    }
}
