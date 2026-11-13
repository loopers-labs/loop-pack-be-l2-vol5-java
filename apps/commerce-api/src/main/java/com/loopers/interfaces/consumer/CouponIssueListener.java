package com.loopers.interfaces.consumer;

import com.loopers.application.coupon.CouponIssueWorker;
import com.loopers.config.CouponIssueListenerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
class CouponIssueListener {

    private final CouponIssueMessageReader reader;
    private final CouponIssueWorker worker;

    CouponIssueListener(CouponIssueMessageReader reader, CouponIssueWorker worker) {
        this.reader = reader;
        this.worker = worker;
    }

    @KafkaListener(
        id = CouponIssueListenerConfig.LISTENER_ID,
        topics = "${kafka.topics.coupon-issues}",
        groupId = CouponIssueListenerConfig.GROUP,
        containerFactory = CouponIssueListenerConfig.FACTORY,
        autoStartup = CouponIssueListenerConfig.AUTO_STARTUP
    )
    void onMessage(ConsumerRecord<String, byte[]> consumerRecord, Acknowledgment acknowledgment) {
        worker.record(reader.read(consumerRecord.value()));
        acknowledgment.acknowledge();
    }
}
