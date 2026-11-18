package com.loopers.interfaces.consumer;

import com.loopers.application.coupon.facade.CouponIssueRequestLoader;
import com.loopers.domain.coupon.vo.CouponIssueRequested;
import com.loopers.support.error.RejectedEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 접수 topic 을 묶음으로 읽어 DB 접수 기록에 적재한다. DB commit 뒤에만 ack 한다 (8주차 D-132).
 * 형식이 계약과 다른 record 는 다시 받아도 같으므로 DLT 로 옮기고 나머지만 적재한다.
 * 그 밖의 예외는 잡지 않고 error handler 로 넘겨 묶음 전체를 다시 처리한다(적재는 멱등)
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueRequestConsumer {

    public static final String LISTENER_ID = "coupon-issue-request-loader";

    private final CouponIssueRequestParser parser;
    private final CouponIssueRequestLoader loader;
    private final KafkaTemplate<String, byte[]> dltTemplate;

    @KafkaListener(
        id = LISTENER_ID,
        idIsGroup = false,
        groupId = "${coupon-issue.consumer-group}",
        topics = "${coupon-issue.topic}",
        containerFactory = CouponIssueRequestConsumerConfig.CONTAINER_FACTORY
    )
    public void consume(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) throws ExecutionException, InterruptedException {
        List<CouponIssueRequested> requests = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                requests.add(parser.parse(record.key(), record.value()));
            } catch (RejectedEventException e) {
                log.error("형식이 계약과 달라 DLT 로 옮김. 접수 기록이 아니라 확인할 대상. topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), e);
                dltTemplate.send(new ProducerRecord<>(record.topic() + ".DLT", record.partition(), record.key(), record.value())).get();
            }
        }
        loader.load(requests);
        ack.acknowledge();
        log.info("접수 기록 적재. records={} loaded={}", records.size(), requests.size());
    }
}
