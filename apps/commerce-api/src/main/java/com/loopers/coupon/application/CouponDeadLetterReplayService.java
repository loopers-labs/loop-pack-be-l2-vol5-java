package com.loopers.coupon.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.coupon.application.port.out.CouponDeadLetterPort;
import com.loopers.coupon.application.port.out.CouponDeadLetterPort.DeadLetter;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * DLT 재처리 (D-2). 자동으로 하지 않는 이유: DLT로 가는 건 보통 재시도로 안 풀리는 실패(버그·데이터)라 자동으로 보내면 같은 실패가 끝없이 돈다.
 * 관리자가 원인을 고친 뒤 부른다. 이 캠페인의 DLT 메시지를 원래 topic으로 다시 보낸다 — consumer가 UNIQUE로 중복을 거른다.
 */
@Slf4j
@Service
public class CouponDeadLetterReplayService {

    private final CouponDeadLetterPort deadLetterPort;
    private final MessageSendPort messageSendPort;
    private final ObjectMapper objectMapper;
    private final String topic;

    public CouponDeadLetterReplayService(CouponDeadLetterPort deadLetterPort, MessageSendPort messageSendPort, ObjectMapper objectMapper,
                                         @Value("${kafka.topics.coupon-issue}") String topic) {
        this.deadLetterPort = deadLetterPort;
        this.messageSendPort = messageSendPort;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * @return 다시 보낸 메시지 수
     */
    public int replay(Long campaignId) {
        List<CompletableFuture<?>> sent = new ArrayList<>();
        for (DeadLetter letter : deadLetterPort.readAll()) {
            if (campaignId.equals(campaignIdOf(letter.value()))) {
                sent.add(messageSendPort.send(topic, letter.key(), letter.value()));
            }
        }
        try {
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get(35, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "DLT 재처리 중 일부를 보내지 못했습니다. 다시 부르면 됩니다(중복은 consumer가 거른다).");
        }
        log.warn("[COUPON_DLT_REPLAYED] campaignId={}, replayed={}", campaignId, sent.size());
        return sent.size();
    }

    private Long campaignIdOf(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("campaignId").canConvertToLong() ? node.path("campaignId").asLong() : null;
        } catch (Exception unreadable) {
            return null; // 계약에 맞지 않는 메시지는 다시 보내도 같은 실패다 — 건너뛴다
        }
    }
}
