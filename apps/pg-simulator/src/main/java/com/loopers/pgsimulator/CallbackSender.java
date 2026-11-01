package com.loopers.pgsimulator;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 거래 snapshot 을 저장 callbackUrl 로 1회 알린다(설계 2.10). 알림마다 전용 worker 2개에 독립 작업으로 제출하므로
 * 수신·완료 순서는 보장하지 않는다. 전송은 거래 상태를 바꾸지 않고, 응답 코드·통신 오류는 결과로만 남기며 재전송하지 않는다.
 */
public final class CallbackSender {

    static final int WORKERS = 2;

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofMilliseconds(300);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(1);
    private static final Timeout POOL_WAIT_TIMEOUT = Timeout.ofMilliseconds(200);

    private final ExecutorService workers;
    private final CloseableHttpClient client;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean closing = new AtomicBoolean();

    CallbackSender(String workerThreadNamePrefix, ObjectMapper objectMapper) {
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
        this.objectMapper = objectMapper;
    }

    /** 제출만 하고 기다리지 않는다. 반환 future 는 항상 완료된다(종료 시에는 ABORTED_BY_CLOSE). */
    public CompletableFuture<CallbackResult> send(TransactionSnapshot transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction 필수");
        }
        DeliveryTask task = new DeliveryTask(transaction.callbackUrl(), notificationBody(transaction));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException e) {
            task.abortUnsent();
        }
        return task.result;
    }

    /** 대기 작업은 보내지 않은 결과로 완료하고, 진행 중 전송은 client 를 즉시 닫아 중단시킨다. */
    void shutdownNow() {
        closing.set(true);
        for (Runnable queued : workers.shutdownNow()) {
            ((DeliveryTask) queued).abortUnsent();
        }
        client.close(CloseMode.IMMEDIATE);
    }

    boolean awaitTermination(Duration timeout) throws InterruptedException {
        return workers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean isTerminated() {
        return workers.isTerminated();
    }

    private String notificationBody(TransactionSnapshot transaction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerOrderId", transaction.orderId());
        body.put("transactionKey", transaction.transactionKey());
        body.put("status", transaction.status());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CallbackFailure classify(Exception e) {
        if (closing.get()) {
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

        private final String callbackUrl;
        private final String body;
        private final CompletableFuture<CallbackResult> result = new CompletableFuture<>();

        private DeliveryTask(String callbackUrl, String body) {
            this.callbackUrl = callbackUrl;
            this.body = body;
        }

        @Override
        public void run() {
            try {
                HttpPost post = new HttpPost(callbackUrl);
                post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
                int httpStatus = client.execute(post, response -> {
                    EntityUtils.consume(response.getEntity());
                    return response.getCode();
                });
                result.complete(new CallbackResult(true, httpStatus, null, null));
            } catch (Exception e) {
                result.complete(new CallbackResult(true, null, classify(e), e.getClass().getName() + ": " + e.getMessage()));
            }
        }

        private void abortUnsent() {
            result.complete(new CallbackResult(false, null, CallbackFailure.ABORTED_BY_CLOSE, null));
        }
    }
}
