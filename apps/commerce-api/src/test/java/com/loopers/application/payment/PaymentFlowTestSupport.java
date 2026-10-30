package com.loopers.application.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.dto.OrderConfirmCommand;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.dto.PaymentStatusUpdateResult;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.domain.common.Money;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.payment.PgHttpClient;
import com.loopers.support.pg.FakePgServer;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * 결제 흐름 테스트의 공통 준비. 실제 DB 와 로컬 테스트 PG fixture 를 함께 쓴다.
 *
 * <p>fixture 서버는 JVM 에서 한 번만 띄우고 요청마다 {@link FakePgServer#reset()} 으로 비운다. 서버 주소가 같아야 이
 * 클래스를 상속한 테스트들이 Spring 컨텍스트를 공유한다. 서버는 daemon 스레드로 돌며 JVM 이 끝날 때 정리한다.
 */
@SpringBootTest
public abstract class PaymentFlowTestSupport {

    protected static final String CARD_NO = "0000-0000-0000-0000";

    private static final FakePgServer PG = startPg();

    private static FakePgServer startPg() {
        try {
            FakePgServer server = FakePgServer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.base-url", PG::baseUrl);
    }

    @Autowired
    protected PaymentResultLookupFacade paymentResultLookupFacade;

    @Autowired
    protected PaymentStatusUpdater paymentStatusUpdater;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    protected OrderFacade orderFacade;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    protected FakePgServer pg;

    // 조회 실패 시나리오가 회로를 열어 두면 컨텍스트를 공유하는 다음 테스트의 조회가 막힌다. 매 테스트 전에 PG 와 회로를 비운다.
    @BeforeEach
    void resetPg() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker(PgHttpClient.LOOKUP_RESILIENCE).reset();
        pg = PG;
    }

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    protected User saveUser(long balance) {
        User user = User.create();
        if (balance > 0) {
            user.chargePoint(new Money(balance));
        }
        return userRepository.save(user);
    }

    protected Product saveProduct(String name, long price, int stock) {
        return productRepository.save(Product.create(1L, name, new Money(price), new Stock(stock)));
    }

    protected Long placeOrder(User user, Product product, int quantity) {
        return orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), quantity))).id();
    }

    protected OrderConfirmCommand command(User user, Long orderId, String attemptKey) {
        return new OrderConfirmCommand(user.getId(), orderId, attemptKey, "TEST", CARD_NO);
    }

    protected static String providerOrderIdOf(Long orderId) {
        return PaymentAttempt.providerOrderIdOf(orderId);
    }

    // ---- DB 를 새로 읽어 확인한다 ----

    protected int stockOf(Product product) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, product.getId());
    }

    protected long balanceOf(User user) {
        return jdbcTemplate.queryForObject("select point_balance from users where id = ?", Long.class, user.getId());
    }

    protected String orderStatusOf(Long orderId) {
        return jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    protected long attemptCount() {
        return jdbcTemplate.queryForObject("select count(*) from payment_attempts", Long.class);
    }

    /** PG 가 이 주문에 대해 가진 첫 거래의 키. */
    protected String pgKeyOf(Long orderId) {
        return pg.transactionsOf(providerOrderIdOf(orderId)).get(0).key();
    }

    protected Map<String, Object> attemptOf(Long orderId) {
        return jdbcTemplate.queryForMap(
            "select status, transaction_key, amount, anomaly from payment_attempts where order_id = ?", orderId
        );
    }

    // 결과 반영은 호출하는 Facade 가 트랜잭션을 연다. Facade 를 거치지 않고 반영 로직만 실행할 때도 같은 경계를 만든다.
    protected PaymentAttemptInfo updateByPaymentRequestResult(Long orderId, PgPaymentRequestResult result) {
        return new TransactionTemplate(transactionManager).execute(
            status -> paymentStatusUpdater.updateByPaymentRequestResult(orderId, result)
        );
    }

    protected PaymentStatusUpdateResult updateByStatusLookupResult(Long orderId, PgPaymentStatusLookupResult lookupResult) {
        return new TransactionTemplate(transactionManager).execute(
            status -> paymentStatusUpdater.updateByStatusLookupResult(orderId, lookupResult)
        );
    }
}
