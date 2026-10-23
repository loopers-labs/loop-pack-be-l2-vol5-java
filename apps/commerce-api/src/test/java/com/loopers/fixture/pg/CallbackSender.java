package com.loopers.fixture.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 저장 거래 snapshot 을 저장 callbackUrl 로 요청한 횟수만큼 보낸다(2.10·5.7). 알림마다 전용 worker 2개에 독립 작업으로
 * 제출하므로 같은 호출의 수신·완료 순서는 보장하지 않는다. 전송은 거래 상태를 바꾸지 않는다.
 * 응답 코드·통신 오류는 결과로만 남기고 자동·수동 재전송을 하지 않는다. commerce 의 풀·회로와 공유하지 않는 전용 client 다.
 */
final class CallbackSender {

    static final int WORKERS = 2;

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofMilliseconds(300);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(1);
    private static final Timeout POOL_WAIT_TIMEOUT = Timeout.ofMilliseconds(200);

    private final ExecutorService workers;
    private final CloseableHttpClient client;
    private final CallbackJournal journal;
    private final LifecycleGuard lifecycle;
    private final ObjectMapper objectMapper;
    private final String workerThreadNamePrefix;

    CallbackSender(String workerThreadNamePrefix, CallbackJournal journal, LifecycleGuard lifecycle,
                   ObjectMapper objectMapper) {
        AtomicInteger sequence = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(WORKERS, runnable -> {
            Thread thread = new Thread(runnable, workerThreadNamePrefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(WORKERS)
                .setMaxConnPerRoute(WORKERS)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT)
                    .setSocketTimeout(RESPONSE_TIMEOUT)
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(POOL_WAIT_TIMEOUT)
                .setResponseTimeout(RESPONSE_TIMEOUT)
                .setRedirectsEnabled(false)
                .build())
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build();
        this.journal = journal;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
        this.workerThreadNamePrefix = workerThreadNamePrefix;
    }

    /** 호출자는 lifecycle 잠금 안에서 count 개 작업을 이미 접수 처리했다. 여기서는 제출만 하고 대기하지 않는다. */
    CompletableFuture<CallbackBatchResult> submit(StoredTransaction transaction, int count) {
        TransactionSnapshot snapshot = transaction.toSnapshot();
        String body = notificationBody(snapshot);
        List<CompletableFuture<CallbackDelivery>> deliveries = new ArrayList<>();
        for (int attempt = 1; attempt <= count; attempt++) {
            DeliveryTask task = new DeliveryTask(attempt, snapshot, transaction.callbackUrl(), body);
            deliveries.add(task.result);
            try {
                workers.execute(task);
            } catch (RejectedExecutionException e) {
                task.abortUnsent();
            }
        }
        return CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new))
            .thenApply(done -> new CallbackBatchResult(snapshot.transactionKey(),
                deliveries.stream().map(CompletableFuture::join).toList()));
    }

    /** 대기 작업은 보내지 않은 결과로 완료하고, 진행 중 전송은 client 를 즉시 닫아 중단시킨다. 어떤 batch 도 미완료로 남지 않는다. */
    void shutdownNow() {
        for (Runnable queued : workers.shutdownNow()) {
            ((DeliveryTask) queued).abortUnsent();
        }
        client.close(CloseMode.IMMEDIATE);
    }

    boolean awaitTermination(Duration timeout) throws InterruptedException {
        return workers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void forceStop() {
        workers.shutdownNow();
    }

    boolean isTerminated() {
        return workers.isTerminated();
    }

    String workerThreadNamePrefix() {
        return workerThreadNamePrefix;
    }

    private String notificationBody(TransactionSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerOrderId", snapshot.orderId());
        body.put("transactionKey", snapshot.transactionKey());
        body.put("status", snapshot.status());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CallbackFailure classify(Exception e) {
        if (lifecycle.isClosing()) {
            return CallbackFailure.ABORTED_BY_CLOSE;
        }
        if (e instanceof ConnectException) {
            return CallbackFailure.CONNECT_FAILED;
        }
        if (e instanceof InterruptedIOException) {
            return CallbackFailure.TIMEOUT;
        }
        return CallbackFailure.IO_ERROR;
    }

    private final class DeliveryTask implements Runnable {

        private final int attempt;
        private final TransactionSnapshot snapshot;
        private final String callbackUrl;
        private final String body;
        private final CompletableFuture<CallbackDelivery> result = new CompletableFuture<>();

        private DeliveryTask(int attempt, TransactionSnapshot snapshot, String callbackUrl, String body) {
            this.attempt = attempt;
            this.snapshot = snapshot;
            this.callbackUrl = callbackUrl;
            this.body = body;
        }

        @Override
        public void run() {
            CallbackJournal.Entry entry = journal.begin(snapshot);
            Integer httpStatus = null;
            CallbackFailure failure = null;
            String errorDetail = null;
            try {
                HttpPost post = new HttpPost(callbackUrl);
                post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
                httpStatus = client.execute(post, response -> {
                    EntityUtils.consume(response.getEntity());
                    return response.getCode();
                });
            } catch (Exception e) {
                failure = classify(e);
                errorDetail = e.getClass().getName() + ": " + e.getMessage();
            } finally {
                // 관측·접수 해제를 결과 완료보다 먼저 해서, batch 완료를 본 테스트가 곧바로 조용한 상태를 보게 한다.
                journal.complete(entry, httpStatus, failure);
                lifecycle.exitCallbackJob();
                result.complete(new CallbackDelivery(attempt, true, httpStatus, failure, errorDetail));
            }
        }

        private void abortUnsent() {
            lifecycle.exitCallbackJob();
            result.complete(new CallbackDelivery(attempt, false, null, CallbackFailure.ABORTED_BY_CLOSE, null));
        }
    }
}
