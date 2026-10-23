package com.loopers.interfaces.api.order;

import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgContainer;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 카드 값은 요청 처리 중 메모리와 PG 생성 요청 본문에만 있어야 한다.
// 결제 요청의 모든 경로를 지난 뒤 로그·DB·응답·테스트 PG 어디에도 식별용 카드 값이 없는지 찾는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class PaymentCardExposureE2ETest {

    private static final String CARD_TYPE = "SENTINEL-TYPE-7Q";
    private static final String CARD_NO = "4000-SENTINEL-0042";
    private static final String NUMERIC_CARD_NO = "4000990042";
    private static final List<String> CARD_VALUES = List.of(CARD_TYPE, CARD_NO, NUMERIC_CARD_NO);
    // 테스트 코드(고객 역할의 TestRestTemplate, 픽스처, 테스트 PG 제어)는 테스트 스레드에서 돈다. Commerce는 요청 처리 스레드에서 돈다.
    private static final Pattern TEST_THREAD_LINE = Pattern.compile("\\[\\s*Test worker\\]");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LoggingSystem loggingSystem;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final List<String> providerOrderIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("현재 로그 설정에서 결제 요청의 모든 경로를 지나도 카드 값이 로그·DB·응답·테스트 PG에 남지 않는다.")
    @Test
    void keepsCardValuesOutAtConfiguredLogLevels(CapturedOutput output) {
        List<String> responses = runEveryCardPath();

        assertNoCardValue(commerceLogs(output), responses);
    }

    @DisplayName("전체 DEBUG와 Spring HTTP TRACE 로그를 켜도 카드 값이 로그에 남지 않는다.")
    @Test
    void keepsCardValuesOutOfVerboseLogs(CapturedOutput output) {
        LogLevel rootLevel = loggingSystem.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel();
        loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
        loggingSystem.setLogLevel("org.springframework.web", LogLevel.TRACE);
        loggingSystem.setLogLevel("org.springframework.http", LogLevel.TRACE);
        List<String> responses;
        try {
            responses = runEveryCardPath();
        } finally {
            loggingSystem.setLogLevel("org.springframework.http", null);
            loggingSystem.setLogLevel("org.springframework.web", null);
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
        }

        assertNoCardValue(commerceLogs(output), responses);
    }

    // 고객이 보낸 요청 본문을 찍는 테스트 client 로그를 빼고, Commerce가 남긴 출력만 검사한다.
    private static String commerceLogs(CapturedOutput output) {
        return output.getAll().lines()
            .filter(line -> !TEST_THREAD_LINE.matcher(line).find())
            .collect(Collectors.joining("\n"));
    }

    // 카드 값이 지나가거나 로그가 남는 경로를 모두 실행하고 응답 본문을 모은다.
    private List<String> runEveryCardPath() {
        List<String> responses = new ArrayList<>();
        responses.add(confirmExpecting(HttpStatus.ACCEPTED, draft(null), card(CARD_TYPE, CARD_NO)));
        responses.add(confirmExpecting(HttpStatus.ACCEPTED, draft("DROP_RESPONSE"), card(CARD_TYPE, CARD_NO)));
        responses.add(confirmExpecting(HttpStatus.OK, draft("REJECT_BEFORE_CREATE"), card(CARD_TYPE, CARD_NO)));

        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.getAndSet(false)) {
                throw new DataAccessResourceFailureException("생성 응답 반영 실패 주입");
            }
            return invocation.callRealMethod();
        }).when(paymentAttemptRepository).markPending(anyLong(), anyString(), any());
        responses.add(confirmExpecting(HttpStatus.ACCEPTED, draft(null), card(CARD_TYPE, CARD_NO)));

        Fixture rejected = draft(null);
        responses.add(confirmExpecting(HttpStatus.BAD_REQUEST, rejected,
            "\"cardType\":\"" + CARD_TYPE + "\",\"cardNo\":" + NUMERIC_CARD_NO));
        responses.add(confirmExpecting(HttpStatus.BAD_REQUEST, rejected,
            "\"cardType\":\"" + CARD_TYPE + "\",\"cardNo\":{\"value\":\"" + CARD_NO + "\"}"));
        responses.add(confirmExpecting(HttpStatus.BAD_REQUEST, rejected, card(" ", CARD_NO)));
        responses.add(confirmExpecting(HttpStatus.BAD_REQUEST, rejected, card(CARD_TYPE, CARD_NO), "not-a-uuid"));
        return responses;
    }

    private void assertNoCardValue(String output, List<String> responses) {
        String pgLogs = TestPgContainer.logs();
        for (String value : CARD_VALUES) {
            assertThat(output).as("Commerce 로그").doesNotContain(value);
            assertThat(responses).as("Commerce 응답").noneMatch(body -> body.contains(value));
            assertThat(tablesContaining(value)).as("Commerce DB").isEmpty();
            assertThat(pgLogs).as("테스트 PG 로그").doesNotContain(value);
            assertThat(providerOrderIds).as("테스트 PG 거래 조회")
                .noneMatch(providerOrderId -> TestPgControl.lookupBodyByOrder(providerOrderId).contains(value));
        }
    }

    // 현재 스키마의 모든 문자열 컬럼에서 값을 찾는다.
    private List<String> tablesContaining(String value) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
            SELECT TABLE_NAME, COLUMN_NAME
            FROM information_schema.columns
            WHERE TABLE_SCHEMA = DATABASE()
              AND DATA_TYPE IN ('char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext', 'enum', 'json')
            """);
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("TABLE_NAME");
            String name = (String) column.get("COLUMN_NAME");
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + table + "` WHERE `" + name + "` LIKE ?", Long.class, "%" + value + "%");
            if (count != null && count > 0) {
                found.add(table + "." + name);
            }
        }
        return found;
    }

    private Fixture draft(String scenario) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(10L);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), 2))));
        String providerOrderId = "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
        if (scenario != null) {
            TestPgControl.scenario(providerOrderId, "{\"scenario\":\"" + scenario + "\"}");
        }
        providerOrderIds.add(providerOrderId);
        return new Fixture(user.getId(), order.getId());
    }

    private static String card(String cardType, String cardNo) {
        return "\"cardType\":\"" + cardType + "\",\"cardNo\":\"" + cardNo + "\"";
    }

    private String confirmExpecting(HttpStatus expected, Fixture fixture, String cardFields) {
        return confirmExpecting(expected, fixture, cardFields, UUID.randomUUID().toString());
    }

    private String confirmExpecting(HttpStatus expected, Fixture fixture, String cardFields, String paymentAttemptKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", fixture.userId().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"paymentAttemptKey\":\"" + paymentAttemptKey + "\"," + cardFields + "}";
        ResponseEntity<String> response = rest.exchange("/api/v1/orders/" + fixture.orderId() + "/confirm",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody() == null ? "" : response.getBody();
    }

    private record Fixture(Long userId, Long orderId) {}
}
