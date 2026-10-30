package com.loopers.payment.scenario;

import com.loopers.payment.domain.PaymentStatus;
import com.loopers.support.pg.LocalTestPgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * 카드 번호 저장·로그 금지 (plan.md 8·9장). 요청 처리 중에만 PG로 전달되고, DB 컬럼·응답·로그 어디에도 남지 않는다.
 */
@DisplayName("카드 번호 저장·로그 금지")
@ExtendWith(OutputCaptureExtension.class)
class CardNumberNotKeptScenarioTest extends PaymentScenarioSupport {

    @DisplayName("plan.md 8·9 확정 요청의 카드 번호는 PG에는 전달되지만, 카드 컬럼은 스키마에 없고 응답·로그(경고 로그가 남는 응답 유실 경로 포함)에도 나오지 않는다.")
    @Test
    void cardNumberIsDeliveredButNotKept(CapturedOutput output) throws Exception {
        // arrange: 응답 유실로 PG 주문 식별자가 경고 로그에 남는 경로를 고른다 — 로그가 실제로 잡히는지도 함께 본다
        PG.delayNextCreateResponse(Duration.ofSeconds(1));
        Long orderId = createOrder(2);

        // act
        confirm(orderId, user, "attempt-1").andExpect(content().string(not(containsString(CARD_NO))));
        awaitPayment(orderId, PaymentStatus.UNKNOWN);
        getPayment(orderId).andExpect(content().string(not(containsString(CARD_NO))));

        // assert: PG에는 전달됐다
        List<LocalTestPgServer.CreateRecord> records = PG.createRecords();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).cardNoPresent()).isTrue();
        assertThat(records.get(0).cardType()).isEqualTo("SAMSUNG");
        // assert: 카드 컬럼이 어느 테이블에도 없다
        List<String> cardColumns = jdbcTemplate.queryForList(
            "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND LOWER(COLUMN_NAME) LIKE '%card%'", String.class);
        assertThat(cardColumns).isEmpty();
        // assert: 로그는 잡혔고(PG 주문 식별자), 카드 번호는 없다
        assertThat(output.getAll()).contains(paymentOf(orderId).getProviderOrderId());
        assertThat(output.getAll()).doesNotContain(CARD_NO).doesNotContain("9814-1451");
    }
}
