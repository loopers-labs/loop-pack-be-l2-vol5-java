package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.ConsumeResult;
import com.loopers.application.purchase.EventSource;
import com.loopers.application.purchase.OrderConfirmedEvent;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.support.PurchaseActivityTables;
import com.loopers.utils.DatabaseCleanUp;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// commerce-api의 같은 이름 테스트와 같은 fixture(modules/kafka testFixtures)를 쓴다. api가 만든 payload가 여기서 처리되는 payload다.
@SpringBootTest
class OrderConfirmedContractTest {
    private static final String FIXTURE = "contracts/order-confirmed-v1.json";

    @Autowired
    private OrderConfirmedEventParser orderConfirmedEventParser;

    @Autowired
    private PurchaseActivityHandler purchaseActivityHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("공유 계약 fixture(주문 42, 상품 7×2·8×1)를 key 42로 파싱·처리하면 구매 활동 2행이 확정 시각과 함께 남는다")
    @Test
    void sharedFixtureIsConsumedAsPurchaseActivity() throws IOException {
        String fixture = fixture();

        OrderEventParseResult parsed = orderConfirmedEventParser.parse("42", fixture.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed).isInstanceOf(OrderEventParseResult.Parsed.class);
        OrderConfirmedEvent event = ((OrderEventParseResult.Parsed) parsed).event();
        ConsumeResult result = purchaseActivityHandler.consume(event, new EventSource("order-events", 0, 0, fixture));
        assertThat(result).isEqualTo(ConsumeResult.RECORDED);
        List<Map<String, Object>> activities = new PurchaseActivityTables(jdbcTemplate).activitiesOf(42);
        assertThat(activities).hasSize(2);
        assertThat(activities.get(0)).containsEntry("product_id", 7L).containsEntry("quantity", 2)
            .containsEntry("confirmed_at", LocalDateTime.parse("2026-10-29T01:00:00"));
        assertThat(activities.get(1)).containsEntry("product_id", 8L).containsEntry("quantity", 1);
    }

    private static String fixture() throws IOException {
        try (InputStream input = new ClassPathResource(FIXTURE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
