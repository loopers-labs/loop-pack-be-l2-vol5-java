package com.loopers.couponissue.adapter.in.kafka;

import com.loopers.config.CouponIssueKafkaConfig;
import com.loopers.couponissue.application.port.in.CouponIssueUseCase;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * coupon-issue-requests → 쿠폰팩 발급 (D-8). record 하나 = DB 트랜잭션 하나 = ack 하나.
 * 정상 반환(발급·이미 발급·품절)일 때만 ack한다. 예외면 error handler가 재시도 3회 뒤 DLT로 보낸다 (D-2: DLT는 관리자 재처리).
 */
@RequiredArgsConstructor
@Component
public class CouponIssueListener {

    private final CouponIssueMessageParser parser;
    private final CouponIssueUseCase couponIssueUseCase;

    @KafkaListener(
        id = CouponIssueKafkaConfig.LISTENER_ID,
        topics = "${kafka.topics.coupon-issue}",
        groupId = "${kafka.groups.coupon-issue}",
        containerFactory = CouponIssueKafkaConfig.RECORD_LISTENER
    )
    public void on(ConsumerRecord<String, String> record, Acknowledgment ack) {
        couponIssueUseCase.issue(parser.parse(record));
        ack.acknowledge();
    }
}
