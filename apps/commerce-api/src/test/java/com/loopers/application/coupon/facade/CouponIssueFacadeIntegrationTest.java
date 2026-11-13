package com.loopers.application.coupon.facade;

import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.domain.coupon.entity.CouponCampaign;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.DiscountType;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.gateway.CouponIssueQueue;
import com.loopers.domain.coupon.gateway.CouponIssueRequestSender;
import com.loopers.domain.coupon.repository.CouponCampaignRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.kafka.CouponIssueTopicReader;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
class CouponIssueFacadeIntegrationTest {

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @Autowired
    private CouponIssueQueue couponIssueQueue;

    @Autowired
    private CouponCampaignRepository couponCampaignRepository;

    @MockitoSpyBean
    private CouponIssueRequestSender couponIssueRequestSender;

    @Autowired
    private CouponIssueTopicReader topicReader;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private CouponCampaign savedCampaign(boolean open) {
        CouponCampaign campaign = new CouponCampaign("행사", 2, DiscountType.FIXED_AMOUNT, 5_000L, "5,000원 할인권");
        if (open) {
            campaign.open();
        }
        return couponCampaignRepository.save(campaign);
    }

    /** 한도 2, 예비 범위 3 으로 연 캠페인 */
    private Long openCampaign() {
        CouponCampaign campaign = savedCampaign(true);
        couponIssueQueue.open(campaign.getId(), 2, 3, "1");
        return campaign.getId();
    }

    @DisplayName("접수하면, ")
    @Nested
    class Request {
        @DisplayName("Kafka 에 접수 기록을 남긴 뒤 WAITING 으로 확정한다. key 는 캠페인:사용자다. (D-132, D-133, D-136)")
        @Test
        void recordsToKafka_thenConfirmsWaiting() {
            // arrange
            Long campaignId = openCampaign();
            Map<TopicPartition, Long> before = topicReader.endOffsets();

            // act
            CouponIssueInfo info = couponIssueFacade.request(campaignId, 7L);

            // assert
            List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
            assertAll(
                () -> assertThat(info.state()).isEqualTo(IssueState.WAITING),
                () -> assertThat(records).hasSize(1),
                () -> assertThat(records.get(0).key()).isEqualTo(campaignId + ":7"),
                () -> assertThat(records.get(0).value()).contains("\"sequence\":1").contains("\"schemaVersion\":1")
            );
        }

        @DisplayName("같은 사용자가 다시 접수하면, Kafka 에 다시 보내지 않고 같은 상태를 돌려준다. (D-117)")
        @Test
        void returnsSameState_withoutResending() {
            // arrange
            Long campaignId = openCampaign();
            couponIssueFacade.request(campaignId, 7L);
            Map<TopicPartition, Long> before = topicReader.endOffsets();

            // act
            CouponIssueInfo again = couponIssueFacade.request(campaignId, 7L);

            // assert
            assertAll(
                () -> assertThat(again.state()).isEqualTo(IssueState.WAITING),
                () -> assertThat(topicReader.recordsSince(before)).isEmpty()
            );
        }

        @DisplayName("한도 밖 예비 범위면 RESERVE 와 현재 예비 위치를, 그 밖이면 SOLD_OUT 을 돌려준다. (D-121, D-149)")
        @Test
        void reservesThenSoldOut() {
            // arrange
            Long campaignId = openCampaign();
            couponIssueFacade.request(campaignId, 1L);
            couponIssueFacade.request(campaignId, 2L);

            // act
            CouponIssueInfo reserve = couponIssueFacade.request(campaignId, 3L);
            CouponIssueInfo soldOut = couponIssueFacade.request(campaignId, 4L);

            // assert
            assertAll(
                () -> assertThat(reserve.state()).isEqualTo(IssueState.RESERVE),
                () -> assertThat(reserve.reservePosition()).isEqualTo(1),
                () -> assertThat(soldOut.state()).isEqualTo(IssueState.SOLD_OUT)
            );
        }

        @DisplayName("닫힌 캠페인이면, CAMPAIGN_NOT_ACTIVE 이다. (D-144)")
        @Test
        void notActive_whenClosed() {
            // arrange
            Long campaignId = openCampaign();
            couponIssueQueue.close(campaignId);

            // act & assert
            assertThatThrownBy(() -> couponIssueFacade.request(campaignId, 7L))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_ACTIVE));
        }
    }

    @DisplayName("Kafka 확인을 받지 못하면, ")
    @Nested
    class SendFailure {
        @DisplayName("503 이고 확정하지 않는다. 다시 요청하면 같은 순번으로 재전송해 확정한다. (D-133, D-134)")
        @Test
        void unavailable_thenResendsOnRetry() {
            // arrange
            Long campaignId = openCampaign();
            doReturn(false).doCallRealMethod().when(couponIssueRequestSender).send(any());

            // act
            Throwable first = catchThrowable(() -> couponIssueFacade.request(campaignId, 7L));
            IssueState afterFailure = couponIssueQueue.read(campaignId, 7L).orElseThrow().state();
            CouponIssueInfo retried = couponIssueFacade.request(campaignId, 7L);

            // assert
            assertAll(
                () -> assertThat(first).isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE)),
                () -> assertThat(afterFailure).isEqualTo(IssueState.PENDING_CONFIRM),
                () -> assertThat(retried.state()).isEqualTo(IssueState.WAITING)
            );
        }

        @DisplayName("실제로는 기록됐는데 결과를 모르면, 503 이고 재요청 때 같은 key 로 한 번 더 보낸다. (D-134, D-164)")
        @Test
        void resendsSameKey_whenRecordedButUnknown() {
            // arrange
            Long campaignId = openCampaign();
            Map<TopicPartition, Long> before = topicReader.endOffsets();
            doAnswer(invocation -> {
                invocation.callRealMethod();
                return false;
            }).doCallRealMethod().when(couponIssueRequestSender).send(any());

            // act
            catchThrowable(() -> couponIssueFacade.request(campaignId, 7L));
            CouponIssueInfo retried = couponIssueFacade.request(campaignId, 7L);

            // assert
            List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
            assertAll(
                () -> assertThat(retried.state()).isEqualTo(IssueState.WAITING),
                () -> assertThat(records).hasSize(2),
                () -> assertThat(records).allMatch(record -> record.key().equals(campaignId + ":7")),
                () -> assertThat(records).allMatch(record -> record.value().contains("\"sequence\":1"))
            );
        }
    }

    @DisplayName("표식이 없으면, DB 로 원인을 가린다. (D-139, D-144)")
    @Nested
    class MarkerMissing {
        @DisplayName("캠페인이 없으면 404 다.")
        @Test
        void notFound_whenCampaignMissing() {
            assertThatThrownBy(() -> couponIssueFacade.request(999L, 7L))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_FOUND));
        }

        @DisplayName("아직 열지 않은 캠페인이면 409 다.")
        @Test
        void notActive_whenCampaignReady() {
            Long campaignId = savedCampaign(false).getId();

            assertThatThrownBy(() -> couponIssueFacade.request(campaignId, 7L))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_ACTIVE));
        }

        @DisplayName("DB 는 OPEN 인데 표식이 없으면(상태 유실) 503 이다.")
        @Test
        void unavailable_whenOpenButMarkerLost() {
            Long campaignId = savedCampaign(true).getId();

            assertThatThrownBy(() -> couponIssueFacade.request(campaignId, 7L))
                .isInstanceOfSatisfying(CoreException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        }
    }
}
