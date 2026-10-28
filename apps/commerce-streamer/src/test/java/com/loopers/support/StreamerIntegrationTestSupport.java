package com.loopers.support;

import com.loopers.config.PurchaseActivityKafkaConfig;
import com.loopers.purchase.adapter.out.persistence.ProductPurchaseActivityJpaRepository;
import com.loopers.purchase.application.port.out.PurchaseActivityPort;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * streamer 통합 테스트의 공통 컨텍스트. 테스트 클래스마다 컨텍스트가 따로 뜨면 각 컨텍스트의 listener가 같은 group으로
 * order-events의 partition을 나눠 가져, 다른 클래스의 record를 엉뚱한 컨텍스트가 처리한다. 그래서 spy를 여기 두고 컨텍스트 하나를 공유한다.
 * spy는 평소 실제 동작을 하고, 실패를 넣는 테스트만 바꾼다. 각 테스트가 끝나면 Mockito가 되돌린다.
 */
@SpringBootTest
public abstract class StreamerIntegrationTestSupport {

    /** E-3·C-6: 저장 중 DB 실패를 넣는다. */
    @MockitoSpyBean
    protected PurchaseActivityPort purchaseActivityPort;

    @Autowired
    protected ProductPurchaseActivityJpaRepository activityRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${kafka.topics.order-events}")
    protected String orderEventsTopic;

    @Value("${kafka.groups.purchase-activity}")
    protected String purchaseActivityGroup;

    @BeforeAll
    static void ensureOrderEventsTopic() {
        // order-events는 api가 만든다. streamer 테스트 클러스터에는 직접 만든다 (partition 3).
        KafkaTestContainersConfig.bootstrapServers();
        KafkaTestSupport.ensureTopic("order-events", 3);
    }

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /**
     * listener의 consumer 3개가 group에 들어와 partition 3개를 나눠 가질 때까지 기다린다.
     * 컨텍스트가 막 떠 rebalance 중에 실패 record를 보내면, 재시도 횟수 추적이 rebalance에서 다시 시작돼 1+3번보다 많이 시도된다 (E-3에서 관찰: 6번).
     */
    @BeforeEach
    void awaitStableAssignment() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(PurchaseActivityKafkaConfig.LISTENER_ID);
        await().atMost(Duration.ofSeconds(30)).until(() -> container.getAssignedPartitions() != null
            && container.getAssignedPartitions().size() == PurchaseActivityKafkaConfig.CONCURRENCY);
    }

    @AfterEach
    void cleanUpDatabase() {
        databaseCleanUp.truncateAllTables();
    }

    protected long consumedCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumed_order_confirmation", Long.class);
    }

    protected long activityCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_purchase_activity", Long.class);
    }
}
