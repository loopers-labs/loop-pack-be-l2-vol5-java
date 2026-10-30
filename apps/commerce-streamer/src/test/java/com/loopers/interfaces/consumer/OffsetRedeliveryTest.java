package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityFacade;
import com.loopers.application.purchase.dto.PurchaseSaveResult;
import com.loopers.support.kafka.ControlledConsumer;
import com.loopers.support.kafka.ExperimentTopic;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실험 목적: <b>읽은 위치와 끝낸 위치는 다르다</b>. DB 에는 반영했지만 offset 을 commit 하지 못한 채 끝난 consumer 의 record
 * 가 실제로 다시 전달되는지, 다시 와도 구매 효과가 한 번뿐인지 본다.
 *
 * <p>통제된 consumer 가 앱의 listener 와 같은 파서·{@link PurchaseActivityFacade#save} 를 직접 호출하고, 첫 실행의 offset
 * commit 만 보류한다. 보이는 것: DB 효과 뒤 offset 이 비어 있으면 같은 record 가 같은 위치에서 다시 온다. 보이지 않는 것:
 * OS 강제 종료·broker 장애·네트워크 분할. 관찰값은 {@code [W6-OBSERVATION]} 로그로 남긴다.
 */
@SpringBootTest
class OffsetRedeliveryTest {

    private static final Logger log = LoggerFactory.getLogger(OffsetRedeliveryTest.class);

    private static final String ORDER_CONFIRMED = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42",
         "orderId":42,"confirmedAt":"2026-09-07T01:00:00Z",
         "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}""";

    @Autowired
    private OrderConfirmedMessageParser parser;

    @Autowired
    private PurchaseActivityFacade facade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    // 상품 ID 순의 "상품x수량". 행 수와 수량이 함께 보인다.
    private List<String> purchaseActivities() {
        return jdbcTemplate.query(
            "select product_id, quantity from product_purchase_activity order by product_id",
            (rs, i) -> rs.getLong("product_id") + "x" + rs.getInt("quantity")
        );
    }

    @DisplayName("DB 에 반영하고 offset 을 commit 하지 않은 채 종료하면, 같은 group 의 새 consumer 가 같은 위치의 record 를 다시 받고 구매 효과는 그대로이며 그 뒤에 n+1 이 commit 된다.")
    @Test
    void redeliversSameRecord_afterDbCommitWithoutOffsetCommit() {
        // arrange: 앞선 record 두 건 뒤에 target 을 보내, target 의 위치 n 이 0 이 아니게 한다
        ExperimentTopic topic = ExperimentTopic.create("redelivery", 1);
        topic.send("42", "filler-1");
        topic.send("42", "filler-2");
        RecordMetadata target = topic.send("42", ORDER_CONFIRMED);
        TopicPartition partition = new TopicPartition(topic.name(), target.partition());
        long n = target.offset();

        // act 1: 첫 consumer 가 n 에서 읽어 DB 에 반영한다. n+1 은 commit 하지 않고 종료한다
        int firstCommitCalls;
        try (ControlledConsumer first = topic.controlledConsumer("first", "earliest")) {
            assertThat(first.autoCommitEnabled()).isFalse();
            first.subscribeStartingAt(partition, n);
            ConsumerRecord<String, byte[]> delivered = first.pollSingle();

            // assert 1: 구매 효과는 있고, committed 는 아직 n 이다
            assertThat(delivered.offset()).isEqualTo(n);
            assertThat(facade.save(parser.parse(delivered))).isEqualTo(PurchaseSaveResult.SAVED);
            assertThat(purchaseActivities()).containsExactly("7x2", "8x1");
            assertThat(topic.committedOffset(partition)).isEqualTo(n);
            firstCommitCalls = first.commitCalls();
        }
        Long committedAfterFirstClose = topic.committedOffset(partition);

        // act 2: 같은 group 의 새 consumer. seek 도 offset reset(none) 도 쓰지 않아 committed 위치에서만 읽을 수 있다
        long committedAfterSecond;
        try (ControlledConsumer second = topic.controlledConsumer("second", "none")) {
            assertThat(second.autoCommitEnabled()).isFalse();
            second.subscribe();
            ConsumerRecord<String, byte[]> redelivered = second.pollSingle();

            // assert 2: 같은 partition·offset 의 같은 record 가 다시 왔고, 구매 효과는 늘지 않는다
            assertThat(redelivered.partition()).isEqualTo(partition.partition());
            assertThat(redelivered.offset()).isEqualTo(n);
            assertThat(new String(redelivered.value(), StandardCharsets.UTF_8)).isEqualTo(ORDER_CONFIRMED);
            assertThat(facade.save(parser.parse(redelivered))).isEqualTo(PurchaseSaveResult.DUPLICATE);
            assertThat(purchaseActivities()).containsExactly("7x2", "8x1");

            // act 3: 업무를 끝냈으니 다음 위치 n+1 을 commit 한다
            second.commit(partition, redelivered.offset() + 1);
            committedAfterSecond = topic.committedOffset(partition);
        }

        // assert 3: committed offset 은 n → n → n+1 이고, 첫 consumer 의 commit 은 시작 위치 지정 한 번뿐이었다
        assertThat(firstCommitCalls).isEqualTo(1);
        assertThat(committedAfterFirstClose).isEqualTo(n);
        assertThat(committedAfterSecond).isEqualTo(n + 1);
        log.info("[W6-OBSERVATION] target={}@{} deliveries=2 committed: beforeProcessing={} afterFirstClose={} afterSecond={}",
            partition, n, n, committedAfterFirstClose, committedAfterSecond);
    }
}
