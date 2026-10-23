package com.loopers.support.pg;

import com.loopers.support.pg.TestPgContract.CreateInput;
import com.loopers.support.pg.TestPgControls.CreateControl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 5주차 로컬 테스트 PG 계약(docs/week5/pg-contract.md 3절)을 따르는 작은 HTTP 서버다.
 * 실제 PG가 아니며, 테스트가 거래 수·호출 수·상태를 관찰하고 실패 장면을 정해진 순서로 만들기 위한 장치다.
 * 테스트가 쓰는 입구는 이 클래스 하나다. 계약 규칙은 {@link TestPgContract}, 실패 통제 상태는 {@link TestPgControls}가 맡는다.
 *
 * <ul>
 *   <li>{@code 127.0.0.1}의 임의 포트에만 바인딩한다. 무작위 지연·성공 확률은 없다.</li>
 *   <li>거래는 생성 시 항상 {@link TestPgStatus#PENDING}이다. 자동 비동기 처리는 없고, 상태는 {@link #complete}로만 바뀐다.</li>
 *   <li>호출자 멱등 키를 받지 않는다. 같은 orderId로 생성 요청이 다시 오면 새 거래를 만든다.</li>
 *   <li>요청은 스레드 풀에서 처리한다. executor를 주지 않은 {@link HttpServer}는 요청을 한 스레드에서 차례로 처리해서,
 *       응답을 붙잡는 동안 다른 요청이 모두 멈춘다 (명세서 13절 실험 E5a).</li>
 *   <li>모든 대기에는 상한({@link #MAX_HOLD})이 있고, {@link #reset()}·{@link #close()}가 붙잡은 응답을 푼다.</li>
 *   <li>카드번호는 거래에 저장하지 않는다. 계약 테스트가 형식을 대조할 수 있도록 받은 요청 본문만 메모리에 남긴다.</li>
 * </ul>
 */
public final class TestPgServer implements AutoCloseable {

    /** 응답을 붙잡는 최대 시간. {@link #releaseHeld()}를 부르지 않아도 이 시간이 지나면 응답한다. */
    public static final Duration MAX_HOLD = TestPgControls.MAX_HOLD;

    /** 이 fixture가 만드는 스레드 이름의 공통 접두어. 서버마다 번호를 더 붙인다 ({@link #threadNamePrefix()}). */
    public static final String THREAD_NAME_PREFIX = "test-pg-";

    private static final AtomicInteger INSTANCES = new AtomicInteger();
    private static final int WORKER_THREADS = 8;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final String BROKEN_JSON = "{\"transactionKey\":";

    private final String threadNamePrefix;
    private final HttpServer server;
    private final ExecutorService executor;
    private final boolean shared;
    private final TestPgControls controls = new TestPgControls();

    private final Map<String, TestPgTransaction> transactions = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicInteger lookupByKeyCount = new AtomicInteger();
    private final AtomicInteger lookupByOrderCount = new AtomicInteger();
    private final List<TestPgRequest> requests = new CopyOnWriteArrayList<>();
    private final List<Throwable> handlerErrors = new CopyOnWriteArrayList<>();

    private TestPgServer(boolean shared) {
        this.shared = shared;
        this.threadNamePrefix = THREAD_NAME_PREFIX + INSTANCES.incrementAndGet() + "-";
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS, daemonThreads(threadNamePrefix));
        this.server = createAndStartOnDaemonThread();
    }

    /** 새 서버를 띄운다. 다 쓰면 {@link #close()}로 닫는다. */
    public static TestPgServer start() {
        return new TestPgServer(false);
    }

    /**
     * JVM에 하나뿐인 서버를 돌려준다. Spring 통합 테스트가 같은 주소를 써서 테스트 컨텍스트 캐시를 재사용하게 하려는 용도다.
     * 테스트 사이에는 {@link #reset()}만 부르고, 닫는 일은 JVM 종료 훅이 맡는다.
     */
    public static TestPgServer shared() {
        return SharedHolder.INSTANCE;
    }

    private static final class SharedHolder {
        private static final TestPgServer INSTANCE = createShared();

        private static TestPgServer createShared() {
            TestPgServer server = new TestPgServer(true);
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, server.threadNamePrefix + "shutdown"));
            return server;
        }
    }

    /** {@code http://127.0.0.1:{port}} 형태의 주소. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 이 서버가 만드는 처리 스레드 이름의 접두어 ({@code test-pg-{서버 번호}-}). 정리됐는지 확인할 때 쓴다. */
    public String threadNamePrefix() {
        return threadNamePrefix;
    }

    // ---- 관찰 ----

    /** 받은 거래 생성 요청 수. 형식 오류·통제로 실패한 요청도 받은 순간 센다. */
    public int createCount() {
        return createCount.get();
    }

    /** 받은 단건 조회 요청 수. */
    public int lookupByKeyCount() {
        return lookupByKeyCount.get();
    }

    /** 받은 주문별 조회 요청 수. */
    public int lookupByOrderCount() {
        return lookupByOrderCount.get();
    }

    /** 받은 모든 요청을 받은 순서대로 돌려준다. */
    public List<TestPgRequest> requests() {
        return List.copyOf(requests);
    }

    /** 저장된 거래 수 (모든 계정). */
    public int transactionCount() {
        return transactions.size();
    }

    /** 주문 번호가 같은 거래를 생성 순서대로 돌려준다 (모든 계정). */
    public List<TestPgTransaction> transactionsOf(String orderId) {
        return transactions.values().stream()
            .filter(transaction -> transaction.orderId().equals(orderId))
            .sorted(Comparator.comparingLong(TestPgTransaction::sequence))
            .toList();
    }

    public Optional<TestPgTransaction> transaction(String transactionKey) {
        return Optional.ofNullable(transactions.get(transactionKey));
    }

    /**
     * 요청 처리 중 이 fixture 코드에서 난 예외. 비어 있지 않으면 fixture 버그다.
     * 이런 예외가 나면 응답 없이 연결을 닫으므로, 클라이언트 쪽에서는 연결 끊김처럼 보인다.
     */
    public List<Throwable> handlerErrors() {
        return List.copyOf(handlerErrors);
    }

    // ---- 통제 ----

    /** 다음 생성 요청을 거래를 저장하지 않고 계약의 422({@link TestPgContract#REJECTED_BEFORE_CREATE})로 거절한다. */
    public void rejectNextCreate() {
        controls.setNextCreate(new CreateControl.Reject());
    }

    /**
     * 다음 생성 요청에 정해 준 상태 코드와 본문으로 응답한다.
     *
     * @param body              JSON 응답 본문. null이나 빈 문자열이면 본문 없이 응답한다
     * @param createTransaction true면 거래를 저장한 뒤 응답한다 (예: 거래는 생겼는데 500을 받는 장면)
     */
    public void respondNextCreate(int status, String body, boolean createTransaction) {
        controls.setNextCreate(new CreateControl.Respond(status, body, createTransaction));
    }

    /** 다음 생성 요청의 거래를 저장한 뒤 응답을 붙잡는다. {@link #releaseHeld()}나 {@link #MAX_HOLD} 뒤에 202를 보낸다. */
    public void holdNextCreateResponse() {
        controls.setNextCreate(new CreateControl.Hold());
    }

    /** 다음 생성 요청의 거래를 저장한 뒤, 응답 헤더를 보내지 않고 연결을 닫는다. */
    public void dropNextCreateResponse() {
        controls.setNextCreate(new CreateControl.Drop());
    }

    /** 다음 {@code count}번의 조회 요청을 {@code failure} 방식으로 실패시킨다. */
    public void failNextLookups(int count, TestPgLookupFailure failure) {
        controls.failNextLookups(count, failure);
    }

    /** {@link #healLookups()}를 부를 때까지 모든 조회 요청을 {@code failure} 방식으로 실패시킨다. */
    public void failLookups(TestPgLookupFailure failure) {
        controls.failLookups(failure);
    }

    /** 조회 실패 통제를 모두 지운다. */
    public void healLookups() {
        controls.healLookups();
    }

    /** 붙잡은 응답을 모두 푼다. 이후에 붙잡는 응답은 다시 붙잡힌다. */
    public void releaseHeld() {
        controls.releaseHeld();
    }

    /** 거래를 최종 상태로 바꾼다. 실제 PG의 비동기 처리 결과를 테스트가 정한 시점에 만든다. */
    public void complete(String transactionKey, TestPgStatus status) {
        if (status == TestPgStatus.PENDING) {
            throw new IllegalArgumentException("최종 상태는 SUCCESS나 FAILED여야 한다.");
        }
        if (transactions.computeIfPresent(transactionKey, (key, transaction) -> transaction.withStatus(status)) == null) {
            throw new IllegalArgumentException("없는 거래다: " + transactionKey);
        }
    }

    /** 생성 요청 없이 거래를 직접 넣는다. 한 주문의 여러 거래, 금액·주문 불일치, 다른 계정 장면에 쓴다. */
    public TestPgTransaction seedTransaction(String account, String orderId, long amount, TestPgStatus status) {
        return store(new CreateInput(account, orderId, "TEST", amount), status);
    }

    /** 이 거래의 현재 상태로 결과 알림(callback) 본문을 만든다 (명세서 3-5절). 보내지는 않는다. */
    public String callbackPayload(String transactionKey) {
        TestPgTransaction transaction = transaction(transactionKey)
            .orElseThrow(() -> new IllegalArgumentException("없는 거래다: " + transactionKey));
        return TestPgContract.callback(transaction);
    }

    /**
     * 붙잡은 응답을 풀고 거래·호출 수·요청 기록·통제·처리 오류를 지운다.
     * 거래 키 일련번호는 이어진다. 테스트 사이에 같은 거래 키가 다시 나오지 않게 하기 위해서다.
     */
    public void reset() {
        controls.reset();
        transactions.clear();
        createCount.set(0);
        lookupByKeyCount.set(0);
        lookupByOrderCount.set(0);
        requests.clear();
        handlerErrors.clear();
    }

    /** 붙잡은 응답을 풀고 서버와 처리 스레드를 정리한다. {@link #shared()} 서버는 닫을 수 없다. */
    @Override
    public void close() {
        if (shared) {
            throw new UnsupportedOperationException("공유 서버는 JVM 종료 때 닫힌다. 테스트 사이에는 reset()을 쓴다.");
        }
        shutdown();
    }

    private void shutdown() {
        controls.releaseHeld();
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 요청 처리 ----

    private void handle(HttpExchange exchange) {
        try {
            route(exchange);
        } catch (IOException e) {
            // 클라이언트가 먼저 연결을 닫은 경우다 (timeout 뒤 늦게 보낸 응답 등). 계약 위반이 아니다.
        } catch (RuntimeException e) {
            handlerErrors.add(e);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(recordOf(exchange, body));

        if (path.equals(TestPgContract.PAYMENTS_PATH)) {
            if (method.equals("POST")) {
                createCount.incrementAndGet();
                handleCreate(exchange, body);
                return;
            }
            if (method.equals("GET")) {
                lookupByOrderCount.incrementAndGet();
                handleLookupByOrder(exchange);
                return;
            }
        } else if (path.startsWith(TestPgContract.PAYMENTS_PATH + "/")) {
            String transactionKey = path.substring(TestPgContract.PAYMENTS_PATH.length() + 1);
            if (method.equals("GET") && !transactionKey.isEmpty() && !transactionKey.contains("/")) {
                lookupByKeyCount.incrementAndGet();
                handleLookupByKey(exchange, transactionKey);
                return;
            }
        }
        respond(exchange, 404, TestPgContract.error(TestPgContract.NOT_FOUND));
    }

    // 형식 검사가 먼저다. 400으로 거절된 요청은 통제를 쓰지 않는다
    private void handleCreate(HttpExchange exchange, String body) throws IOException {
        CreateInput input = TestPgContract.validateCreate(accountOf(exchange), body);
        if (input == null) {
            respond(exchange, 400, TestPgContract.error(TestPgContract.INVALID_REQUEST));
            return;
        }

        CreateControl control = controls.takeNextCreate();
        if (control == null) {
            respond(exchange, 202, TestPgContract.transaction(store(input, TestPgStatus.PENDING)));
            return;
        }
        switch (control) {
            case CreateControl.Reject reject -> respond(exchange, 422, TestPgContract.rejection(input));
            case CreateControl.Respond custom -> {
                if (custom.createTransaction()) {
                    store(input, TestPgStatus.PENDING);
                }
                respond(exchange, custom.status(), custom.body());
            }
            case CreateControl.Hold hold -> {
                TestPgTransaction transaction = store(input, TestPgStatus.PENDING);
                controls.awaitRelease();
                respond(exchange, 202, TestPgContract.transaction(transaction));
            }
            case CreateControl.Drop drop -> store(input, TestPgStatus.PENDING); // 응답 없이 handle()의 finally가 연결을 닫는다
        }
    }

    // 실패 통제가 계정 확인보다 먼저다. PG의 기반 장애를 흉내 내기 때문이다
    private void handleLookupByKey(HttpExchange exchange, String transactionKey) throws IOException {
        if (applyLookupFailure(exchange)) {
            return;
        }
        String account = accountOf(exchange);
        if (TestPgContract.isBlank(account)) {
            respond(exchange, 400, TestPgContract.error(TestPgContract.INVALID_REQUEST));
            return;
        }
        TestPgTransaction transaction = transactions.get(transactionKey);
        if (transaction == null || !transaction.account().equals(account)) {
            respond(exchange, 404, TestPgContract.error(TestPgContract.NOT_FOUND));
            return;
        }
        respond(exchange, 200, TestPgContract.transaction(transaction));
    }

    private void handleLookupByOrder(HttpExchange exchange) throws IOException {
        if (applyLookupFailure(exchange)) {
            return;
        }
        String account = accountOf(exchange);
        String orderId = queryParam(exchange.getRequestURI().getRawQuery(), "orderId");
        if (TestPgContract.isBlank(account) || TestPgContract.isBlank(orderId)) {
            respond(exchange, 400, TestPgContract.error(TestPgContract.INVALID_REQUEST));
            return;
        }
        List<TestPgTransaction> sameAccount = transactionsOf(orderId).stream()
            .filter(transaction -> transaction.account().equals(account))
            .toList();
        respond(exchange, 200, TestPgContract.transactions(sameAccount));
    }

    // 실패 응답을 보냈거나 연결을 닫아야 하면 true. HOLD는 붙잡았다가 정상 처리를 이어 가도록 false다
    private boolean applyLookupFailure(HttpExchange exchange) throws IOException {
        TestPgLookupFailure failure = controls.takeLookupFailure();
        if (failure == null) {
            return false;
        }
        switch (failure) {
            case SERVER_ERROR -> respond(exchange, 500, TestPgContract.error(TestPgContract.SERVER_ERROR));
            case INVALID_JSON -> respond(exchange, 200, BROKEN_JSON);
            case DROP -> {
                // 응답 없이 handle()의 finally가 연결을 닫는다
            }
            case HOLD -> {
                controls.awaitRelease();
                return false;
            }
        }
        return true;
    }

    private TestPgTransaction store(CreateInput input, TestPgStatus status) {
        long next = sequence.incrementAndGet();
        TestPgTransaction transaction = new TestPgTransaction(
            TestPgContract.transactionKey(next), input.account(), input.orderId(), input.amount(), status, input.cardType(), next
        );
        transactions.put(transaction.transactionKey(), transaction);
        return transaction;
    }

    // ---- HTTP 도우미 ----

    private static String accountOf(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst(TestPgContract.ACCOUNT_HEADER);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null || body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static TestPgRequest recordOf(HttpExchange exchange, String body) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return new TestPgRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawPath(),
            exchange.getRequestURI().getRawQuery(),
            Collections.unmodifiableMap(headers),
            body
        );
    }

    // query는 서블릿 컨테이너처럼 폼 인코딩 규칙으로 푼다 ('+'는 공백, '%2B'는 '+')
    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (URLDecoder.decode(key, StandardCharsets.UTF_8).equals(name)) {
                return separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // HttpServer의 디스패처 스레드는 서버를 시작한 스레드의 daemon 여부를 물려받는다.
    // 테스트 스레드(daemon 아님)에서 시작하면 닫지 않은 서버가 JVM 종료를 막으므로 daemon 스레드에서 시작한다 (명세서 13절 실험 E7)
    private HttpServer createAndStartOnDaemonThread() {
        AtomicReference<HttpServer> created = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
                httpServer.setExecutor(executor);
                httpServer.createContext("/", this::handle);
                httpServer.start();
                created.set(httpServer);
            } catch (IOException e) {
                failure.set(e);
            }
        }, threadNamePrefix + "starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join(SHUTDOWN_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (created.get() == null) {
            executor.shutdownNow();
            throw new UncheckedIOException("테스트 PG 서버를 띄우지 못했다", failure.get() != null ? failure.get() : new IOException("시작 시간 초과"));
        }
        return created.get();
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger number = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
