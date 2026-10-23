package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentGatewayResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.domain.payment.PaymentGatewayResult.Failure.HTTP_STATUS;
import static com.loopers.domain.payment.PaymentGatewayResult.Failure.INVALID_RESPONSE;
import static com.loopers.domain.payment.PaymentGatewayResult.Failure.NETWORK;
import static com.loopers.domain.payment.PaymentGatewayResult.Failure.TIMEOUT;

/** Real HTTP adapter for the assignment's loopback-only test PG contract. */
public final class HttpTestPgClient implements PaymentGateway, AutoCloseable {
    private static final String ACCOUNT = "135135";
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private final URI baseUri;
    private final URI callbackUri;
    private final ObjectMapper json;
    private final PgHttpTimeouts timeouts;
    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor deadlines;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpTestPgClient(URI baseUri, URI callbackUri, ObjectMapper mapper, PgHttpTimeouts timeouts) {
        this(baseUri, callbackUri, mapper, timeouts, 20);
    }

    public HttpTestPgClient(URI baseUri, URI callbackUri, ObjectMapper mapper, PgHttpTimeouts timeouts,
                            int maxConnections) {
        validateLocalUri(baseUri);
        validateLocalUri(callbackUri);
        if ((!baseUri.getPath().isEmpty() && !baseUri.getPath().equals("/"))
            || !"/api/v1/payments/callback".equals(callbackUri.getPath()) || maxConnections < 1) {
            throw new IllegalArgumentException("Invalid local test PG endpoint configuration");
        }
        this.baseUri = baseUri;
        this.callbackUri = callbackUri;
        this.timeouts = Objects.requireNonNull(timeouts);
        this.json = Objects.requireNonNull(mapper).copy()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(maxConnections).setMaxConnPerRoute(maxConnections)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(timeout(timeouts.connect())).build()).build();
        this.client = HttpClients.custom().setConnectionManager(pool)
            .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
            .disableContentCompression().disableAuthCaching().build();
        this.deadlines = new ScheduledThreadPoolExecutor(1,
            task -> Thread.ofPlatform().daemon(true).name("test-pg-http-deadline").unstarted(task));
        this.deadlines.setRemoveOnCancelPolicy(true);
    }

    @Override
    public PaymentGatewayResult create(PaymentCreateRequest request) {
        Objects.requireNonNull(request);
        HttpPost post = new HttpPost(uri(null, null));
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("orderId", request.providerOrderId(), "amount", request.amount(),
                "cardType", request.cardType(), "cardNo", request.cardNo(), "callbackUrl", callbackUri.toString()));
        } catch (JsonProcessingException invalidInput) {
            throw new IllegalArgumentException("Cannot encode test PG input");
        }
        try {
            post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
            return execute(post, Operation.CREATE, request.providerOrderId(), request.amount());
        } finally {
            Arrays.fill(body, (byte) 0);
            post.setEntity(null);
        }
    }

    @Override
    public PaymentGatewayResult findByTransactionKey(String transactionKey) {
        requireIdentifier(transactionKey, 1);
        return execute(new HttpGet(uri(transactionKey, null)), Operation.KEY, transactionKey, 0);
    }

    @Override
    public PaymentGatewayResult findByProviderOrderId(String providerOrderId) {
        requireIdentifier(providerOrderId, 6);
        return execute(new HttpGet(uri(null, providerOrderId)), Operation.ORDER, providerOrderId, 0);
    }

    private PaymentGatewayResult execute(HttpUriRequestBase request, Operation operation, String expectedId,
                                         long expectedAmount) {
        if (closed.get()) {
            throw new IllegalStateException("Test PG HTTP client is closed");
        }
        boolean create = operation == Operation.CREATE;
        request.setHeader("X-USER-ID", ACCOUNT);
        request.setHeader("Accept", "application/json");
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(timeout(timeouts.pool()))
            .setResponseTimeout(timeout(create ? timeouts.createResponse() : timeouts.lookupResponse()))
            .setAuthenticationEnabled(false).setRedirectsEnabled(false).setHardCancellationEnabled(true)
            .setProtocolUpgradeEnabled(false).build());
        AtomicBoolean expired = new AtomicBoolean();
        var alarm = deadlines.schedule(() -> {
            expired.set(true);
            request.cancel();
        }, (create ? timeouts.createTotal() : timeouts.lookupTotal()).toMillis(), TimeUnit.MILLISECONDS);
        try (ClassicHttpResponse response = client.executeOpen(null, request, HttpClientContext.create())) {
            int status = response.getCode();
            if (operation == Operation.KEY && status == 404) {
                discardResponse(request, response);
                return PaymentGatewayResult.notFound();
            }
            if (status != (create ? 202 : 200) && !(create && status == 422)) {
                discardResponse(request, response);
                return PaymentGatewayResult.unknown(HTTP_STATUS);
            }
            byte[] bytes = response.getEntity() == null ? new byte[0]
                : response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                discardResponse(request, response);
                return PaymentGatewayResult.unknown(INVALID_RESPONSE);
            }
            JsonNode root = json.readTree(bytes);
            PaymentGatewayResult result = status == 422
                ? rejection(root, expectedId, expectedAmount) : observation(root, operation, expectedId, expectedAmount);
            return expired.get() ? PaymentGatewayResult.unknown(TIMEOUT) : result;
        } catch (JsonProcessingException | InvalidPgResponse invalid) {
            request.cancel();
            return PaymentGatewayResult.unknown(INVALID_RESPONSE);
        } catch (IOException unavailable) {
            request.cancel();
            return PaymentGatewayResult.unknown(expired.get() || unavailable instanceof SocketTimeoutException
                || unavailable instanceof ConnectionRequestTimeoutException ? TIMEOUT : NETWORK);
        } finally {
            alarm.cancel(false);
        }
    }

    private static void discardResponse(HttpUriRequestBase request, ClassicHttpResponse response) {
        request.cancel();
        // The connection is already discarded. Do not drain its body during close or replace a known HTTP result.
        response.setEntity(null);
    }

    private PaymentGatewayResult rejection(JsonNode root, String expectedOrder, long expectedAmount) {
        if (root != null && root.isObject()
            && "TEST_PG_REJECTED_BEFORE_CREATE".equals(text(root, "code"))
            && root.path("transactionCreated").isBoolean() && !root.path("transactionCreated").booleanValue()
            && expectedOrder.equals(text(root, "orderId")) && amount(root) == expectedAmount) {
            return PaymentGatewayResult.rejected();
        }
        return PaymentGatewayResult.unknown(INVALID_RESPONSE);
    }

    private PaymentGatewayResult observation(JsonNode root, Operation operation, String expectedId, long expectedAmount) {
        if (operation == Operation.ORDER) {
            if (root == null || !root.isArray()) {
                throw new InvalidPgResponse();
            }
            List<PaymentTransaction> transactions = new ArrayList<>();
            for (JsonNode node : root) {
                transactions.add(transaction(node));
            }
            if (transactions.isEmpty()) {
                return PaymentGatewayResult.notFound();
            }
            if (transactions.size() > 1 || !expectedId.equals(transactions.getFirst().providerOrderId())) {
                return PaymentGatewayResult.conflict(transactions);
            }
            return PaymentGatewayResult.observed(transactions);
        }
        PaymentTransaction transaction = transaction(root);
        boolean matches = operation == Operation.CREATE
            ? expectedId.equals(transaction.providerOrderId()) && expectedAmount == transaction.amount()
            : expectedId.equals(transaction.transactionKey());
        return matches ? PaymentGatewayResult.observed(List.of(transaction))
            : PaymentGatewayResult.conflict(List.of(transaction));
    }

    private PaymentTransaction transaction(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidPgResponse();
        }
        String key = text(root, "transactionKey");
        String order = text(root, "orderId");
        if (key == null || key.isBlank() || order == null || order.isBlank() || order.length() < 6) {
            throw new InvalidPgResponse();
        }
        PaymentTransaction.Status status;
        try {
            status = PaymentTransaction.Status.valueOf(text(root, "status"));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidPgResponse();
        }
        return new PaymentTransaction(ACCOUNT, order, key, amount(root), status);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static long amount(JsonNode root) {
        JsonNode value = root.path("amount");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new InvalidPgResponse();
        }
        return value.longValue();
    }

    private URI uri(String transactionKey, String orderId) {
        URIBuilder builder = new URIBuilder(baseUri).setPathSegments("api", "v1", "payments");
        if (transactionKey != null) {
            builder.appendPathSegments(transactionKey);
        }
        if (orderId != null) {
            builder.addParameter("orderId", orderId);
        }
        try {
            return builder.build();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid test PG request identifier");
        }
    }

    private static void requireIdentifier(String value, int minimumLength) {
        if (value == null || value.isBlank() || value.length() < minimumLength) {
            throw new IllegalArgumentException("Invalid test PG request identifier");
        }
    }

    private static void validateLocalUri(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
            || uri.getPort() < 1 || uri.getPort() > 65_535 || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Test PG endpoints must be fixed HTTP loopback URLs with a port");
        }
    }

    private static Timeout timeout(Duration value) {
        return Timeout.ofMilliseconds(value.toMillis());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close(CloseMode.IMMEDIATE);
            deadlines.shutdownNow();
        }
    }

    private enum Operation {
        CREATE, KEY, ORDER
    }

    private static final class InvalidPgResponse extends RuntimeException {
        private InvalidPgResponse() {
            super("Invalid test PG response");
        }
    }
}
