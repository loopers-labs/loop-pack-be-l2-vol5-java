package com.loopers.fixture;

import com.loopers.domain.payment.PaymentModel;
import com.loopers.fixture.pg.CreateOutcome;
import com.loopers.fixture.pg.PgFixtureServer;
import com.loopers.fixture.pg.PgOperation;
import com.loopers.fixture.pg.ResponsePlan;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

@ContextConfiguration(initializers = PgFixtureContextInitializer.class)
public abstract class PgTestSupport {

    @Autowired
    protected PgFixtureServer pg;
    @Autowired
    protected CircuitBreakerRegistry circuits;

    @BeforeEach
    void resetPg() {
        awaitPgIdle();
        pg.reset();
        circuits.getAllCircuitBreakers().forEach(circuit -> circuit.reset());
    }

    @AfterEach
    void finishPg() {
        awaitPgIdle();
    }

    protected void awaitPgIdle() {
        await().atMost(Duration.ofSeconds(5)).until(() ->
            pg.state().activeRequests() == 0 && pg.state().activeCallbackJobs() == 0);
    }

    protected void success(Long orderId) {
        pg.enqueuePlan(PgOperation.CREATE, PaymentModel.providerOrderIdOf(orderId),
            ResponsePlan.outcome(CreateOutcome.SUCCESS));
    }
}
