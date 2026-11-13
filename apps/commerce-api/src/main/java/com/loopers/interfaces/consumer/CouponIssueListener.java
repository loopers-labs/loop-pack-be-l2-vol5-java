package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueMessage;
import com.loopers.application.coupon.CouponIssueWorker;
import com.loopers.config.CouponIssueListenerConfig;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
class CouponIssueListener {

    private final ObjectMapper objectMapper;
    private final CouponIssueWorker worker;

    CouponIssueListener(ObjectMapper objectMapper, CouponIssueWorker worker) {
        this.objectMapper = objectMapper;
        this.worker = worker;
    }

    @KafkaListener(
        id = CouponIssueListenerConfig.LISTENER_ID,
        topics = "${kafka.topics.coupon-issues}",
        groupId = CouponIssueListenerConfig.GROUP,
        containerFactory = CouponIssueListenerConfig.FACTORY,
        autoStartup = CouponIssueListenerConfig.AUTO_STARTUP
    )
    void onMessages(List<ConsumerRecord<String, byte[]>> records, Acknowledgment acknowledgment) {
        List<CouponIssue> readable = new ArrayList<>(records.size());
        int unreadable = -1;
        RuntimeException unreadableCause = null;
        for (int i = 0; i < records.size() && unreadable < 0; i++) {
            try {
                readable.add(CouponIssueMessage.read(objectMapper, records.get(i).value()));
            } catch (RuntimeException e) {
                unreadable = i;
                unreadableCause = new UnreadableCouponIssue(e);
            }
        }
        try {
            worker.record(readable);
        } catch (CouponIssueWorker.RecordFailed e) {
            throw new BatchListenerFailedException("쿠폰 기록이 실패했다", e.getCause(), e.index());
        }
        if (unreadable >= 0) {
            throw new BatchListenerFailedException("쿠폰 접수 메시지를 읽을 수 없다", unreadableCause, unreadable);
        }
        acknowledgment.acknowledge();
    }
}
