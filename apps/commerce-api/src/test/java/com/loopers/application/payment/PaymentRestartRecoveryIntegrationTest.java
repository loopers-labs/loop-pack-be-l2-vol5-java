package com.loopers.application.payment;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// 생성 응답을 잃은 뒤 Commerce가 재시작된 경우를 같은 DB를 쓰는 새 애플리케이션 컨텍스트로 재현한다.
// 새 인스턴스는 스키마를 다시 만들지 않고(ddl-auto: none) 저장된 시도·주문·품목 스냅샷으로 이어서 처리한다.
@SpringBootTest
class PaymentRestartRecoveryIntegrationTest {

    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @Autowired
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

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("응답 유실 뒤 재시작한 인스턴스는 reconcile 없이 주기 조회만으로 기존 거래를 찾아 종결하고 생성을 다시 보내지 않는다.")
    @Test
    void finishesDroppedAttemptAfterRestart() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 3_500L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        String providerOrderId = "LP-ORD-%010d-%s".formatted(order.getId(), suffix);
        TestPgControl.scenario(providerOrderId, "{\"scenario\":\"DROP_RESPONSE\"}");
        paymentRequestFacade.requestPayment(user.getId(), order.getId(), UUID.randomUUID().toString(), "TEST", "0000");
        assertThat(paymentAttemptRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.UNKNOWN);
        TestPgControl.complete(providerOrderId);
        jdbcTemplate.update("UPDATE payment_attempts SET updated_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 MINUTE) "
            + "WHERE order_id = ?", order.getId());

        // 실행 인자는 test 프로필 설정(ddl-auto: create)보다 우선하므로 기존 스키마와 데이터를 그대로 쓴다.
        // 관리 포트는 다른 테스트 컨텍스트와 겹치지 않게 무작위로 둔다.
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(CommerceApiApplication.class)
            .web(WebApplicationType.NONE)
            .profiles("test")
            .run("--spring.jpa.hibernate.ddl-auto=none", "--payment.periodic-check.enabled=false",
                "--management.server.port=0")) {
            restarted.getBean(PaymentPeriodicCheck.class).checkDue();
        }

        assertThat(paymentAttemptRepository.findByOrderId(order.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getItems())
            .singleElement().extracting(OrderItem::getQuantity).isEqualTo(QUANTITY);
        assertThat(productRepository.findById(saved.getId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - QUANTITY);
        assertThat(TestPgControl.stats(providerOrderId).createRequests()).isEqualTo(1);
    }
}
