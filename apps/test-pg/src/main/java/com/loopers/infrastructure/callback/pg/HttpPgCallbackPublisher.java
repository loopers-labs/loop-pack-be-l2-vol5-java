package com.loopers.infrastructure.callback.pg;

import com.loopers.application.pg.gateway.PgCallbackGateway;
import com.loopers.domain.pg.model.PgScenario;
import com.loopers.domain.pg.model.PgStatus;
import com.loopers.domain.pg.model.PgTransaction;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class HttpPgCallbackPublisher implements PgCallbackGateway {
    private final TaskScheduler scheduler;
    private final RestClient restClient;

    public HttpPgCallbackPublisher(@Qualifier("pgCallbackScheduler") TaskScheduler scheduler,
                                   @Qualifier("pgCallbackRestClient") RestClient restClient) {
        this.scheduler = scheduler;
        this.restClient = restClient;
    }

    @Override
    public void publish(PgTransaction completed) {
        if (completed.status() == PgStatus.PENDING) {
            throw new IllegalArgumentException("종결된 거래만 callback을 발송합니다.");
        }
        if (completed.scenario().callbackMode() == PgScenario.CallbackMode.DROP) {
            return;
        }
        try {
            scheduler.schedule(() -> send(completed), Instant.now().plusMillis(completed.scenario().callbackDelayMs()));
        } catch (RejectedExecutionException exception) {
            log.warn("PG callback 예약 실패: transactionKey={}", completed.transactionKey());
        }
    }

    private void send(PgTransaction completed) {
        try {
            restClient.post().uri(completed.callbackUrl()).body(new CallbackNotification(
                    completed.orderId(), completed.transactionKey(), completed.status().name()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            // best-effort 한 번. 원문 응답·주소·입력을 로그에 포함하지 않는다.
            log.warn("PG callback 전달 실패: transactionKey={}", completed.transactionKey());
        }
    }

    public record CallbackNotification(String providerOrderId, String transactionKey, String status) {}
}
