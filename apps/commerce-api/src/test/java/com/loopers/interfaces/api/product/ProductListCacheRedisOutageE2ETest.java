package com.loopers.interfaces.api.product;

import com.loopers.application.payment.PaymentResultService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.support.testpg.TestPgControl;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.interfaces.api.order.OrderV1Dto.OrderItemRequest;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.AdminMockMvcClient;
import com.loopers.interfaces.api.admin.product.ProductV1Dto;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Redis 중단·재시작 중 상품 목록과 주문")
class ProductListCacheRedisOutageE2ETest {

    // 재시작 뒤에도 같은 주소로 다시 연결되도록 이 테스트만 쓰는 Redis를 고정 포트로 띄운다.
    // 로컬 Compose와 같이 AOF 영속화를 켠다. master·replica 설정이 모두 이 컨테이너를 가리킨다.
    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withCommand("redis-server", "--appendonly", "yes")
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))
        ));

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
    }

    @AfterAll
    static void removeRedis() {
        REDIS.stop();
    }

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentResultService paymentResultService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;
    private boolean statisticsWereEnabled;
    private AdminMockMvcClient adminClient;
    private Brand brand;
    private Product air;
    private Product max;

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        adminClient = new AdminMockMvcClient(mockMvc, objectMapper);
        brand = brandRepository.save(Brand.create("Nike"));
        air = saveProductWithStock("Air", 1_000L);
        max = saveProductWithStock("Max", 2_000L);
    }

    @AfterEach
    void tearDown() {
        startRedisIfStopped();
        flushRedisAfterReconnect();
        statistics.setStatisticsEnabled(statisticsWereEnabled);
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("Redis가 멈추면 제한된 대기 뒤 DB 결과를 반환하고 주문은 DB 규칙대로 처리하며, 같은 Redis가 다시 시작되면 다시 연결해 캐시를 채운다.")
    void servesDbResultAndOrdersDuringOutage_andReconnectsAfterRestart() throws Exception {
        List<ProductCustomerV1Dto.ProductResponse> beforeOutage = customerList();
        statistics.clear();
        assertThat(customerList()).isEqualTo(beforeOutage);
        assertThat(statistics.getPrepareStatementCount()).as("hit은 삭제 확인 1회만 실행한다.").isEqualTo(1L);

        stopRedis();

        for (int request = 0; request < 3; request++) {
            long startedAt = System.nanoTime();
            List<ProductCustomerV1Dto.ProductResponse> duringOutage = customerList();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(duringOutage).isEqualTo(beforeOutage);
            assertThat(elapsed).as("Redis 대기는 timeout으로 제한된다.").isLessThan(Duration.ofSeconds(1));
        }
        User user = saveUserWithPoints(10_000L);
        Long orderId = createAndConfirmOrder(user.getId(), air.getId(), 2);
        OrderV1Dto.OrderResponse order = getOrder(user.getId(), orderId);
        assertThat(order.status()).isEqualTo("CONFIRMED");
        assertThat(order.totalAmount()).isEqualTo(2_000L);
        assertThat(productRepository.findById(air.getId()).orElseThrow().getStock().amount()).isEqualTo(8L);
        assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(10_000L);

        startRedisIfStopped();

        assertThat(customerList()).isEqualTo(beforeOutage);
        updatePrice(max.getId(), 500L);
        assertThat(customerList())
            .extracting(ProductCustomerV1Dto.ProductResponse::id, ProductCustomerV1Dto.ProductResponse::price)
            .containsExactly(tuple(max.getId(), 500L), tuple(air.getId(), 1_000L));
        statistics.clear();
        customerList();
        assertThat(statistics.getPrepareStatementCount()).as("재시작 뒤 다시 채운 캐시를 사용한다.").isEqualTo(1L);
    }

    @Test
    @DisplayName("Redis가 멈춘 동안의 가격 수정은 커밋되고, 재시작 뒤 복원된 이전 표시는 만료 뒤 DB 결과로 복구된다.")
    void keepsCommitDuringOutage_andRecoversAfterExpiry() throws Exception {
        customerList();

        stopRedis();
        updatePrice(air.getId(), 3_000L);
        assertThat(productRepository.findById(air.getId()).orElseThrow().getPrice()).isEqualTo(3_000L);
        assertThat(customerList()).extracting(ProductCustomerV1Dto.ProductResponse::price)
            .as("Redis 중단 중에는 DB 결과를 반환한다.")
            .containsExactly(2_000L, 3_000L);

        startRedisIfStopped();

        assertThat(customerList()).extracting(ProductCustomerV1Dto.ProductResponse::price)
            .as("AOF로 복원된 이전 항목은 무효화 실패와 같이 만료 전까지 이전 표시를 허용한다.")
            .containsExactly(1_000L, 2_000L);

        redisTemplate.delete(redisTemplate.keys("product:list:v1:*"));

        assertThat(customerList())
            .extracting(ProductCustomerV1Dto.ProductResponse::id, ProductCustomerV1Dto.ProductResponse::price)
            .containsExactly(tuple(max.getId(), 2_000L), tuple(air.getId(), 3_000L));
    }

    private void stopRedis() {
        dockerClient().stopContainerCmd(REDIS.getContainerId()).exec();
    }

    private void startRedisIfStopped() {
        Boolean running = dockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getRunning();
        if (!Boolean.TRUE.equals(running)) {
            dockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if ("PONG".equals(redisTemplate.execute(connection -> connection.ping(), true))) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // 다시 연결될 때까지 기다린다.
            }
            sleepBriefly();
        }
        throw new IllegalStateException("Redis 재시작 뒤 30초 안에 다시 연결되지 않았습니다.");
    }

    // 재시작 직후에는 PING이 한 번 성공해도 다음 명령이 앱의 짧은 명령 timeout(100ms)을 넘길 수 있다.
    // 정리용 비우기도 성공할 때까지 짧게 다시 시도한다.
    private void flushRedisAfterReconnect() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                redisTemplate.execute(connection -> {
                    connection.serverCommands().flushAll();
                    return null;
                }, true);
                return;
            } catch (RuntimeException exception) {
                if (System.nanoTime() >= deadline) {
                    throw exception;
                }
                sleepBriefly();
            }
        }
    }

    private DockerClient dockerClient() {
        return DockerClientFactory.instance().client();
    }

    private Product saveProductWithStock(String name, long price) {
        Product product = Product.create(brand.getId(), name, price);
        product.changeStockTo(10L);
        return productRepository.save(product);
    }

    private User saveUserWithPoints(long amount) {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(amount)));
        return user;
    }

    private void updatePrice(Long productId, long price) {
        ResponseEntity<ApiResponse<Object>> response = adminClient.exchange(
            "/api-admin/v1/products/" + productId,
            HttpMethod.PUT,
            new HttpEntity<>(new ProductV1Dto.UpdateRequest(productRepository.findById(productId).orElseThrow().getName(), price)),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long createAndConfirmOrder(Long userId, Long productId, int quantity) throws Exception {
        OrderV1Dto.CreateRequest request = new OrderV1Dto.CreateRequest(List.of(new OrderItemRequest(productId, quantity)));
        MvcResult created = mockMvc.perform(post("/api/v1/orders")
                .header("X-USER-ID", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(HttpStatus.CREATED.value());
        Long orderId = readData(created, new TypeReference<ApiResponse<OrderV1Dto.OrderResponse>>() {}).id();
        MvcResult confirmed = mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header("X-USER-ID", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentAttemptKey\":\"" + UUID.randomUUID() + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\"}"))
            .andReturn();
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        completePgPayment(orderId);
        return orderId;
    }

    // 결제 요청 뒤 테스트 PG에서 거래를 처리하고 결과를 확인해 확정 주문을 만든다.
    private void completePgPayment(Long orderId) {
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId).orElseThrow();
        TestPgControl.complete(attempt.getProviderOrderId());
        assertThat(paymentResultService.check(attempt.getId()).payment().orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    private OrderV1Dto.OrderResponse getOrder(Long userId, Long orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + orderId).header("X-USER-ID", userId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        return readData(result, new TypeReference<ApiResponse<OrderV1Dto.OrderResponse>>() {});
    }

    private List<ProductCustomerV1Dto.ProductResponse> customerList() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/products")
                .param("brandId", brand.getId().toString())
                .param("sort", "price_asc"))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        return readData(result, new TypeReference<ApiResponse<List<ProductCustomerV1Dto.ProductResponse>>>() {});
    }

    private <T> T readData(MvcResult result, TypeReference<ApiResponse<T>> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type).data();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
