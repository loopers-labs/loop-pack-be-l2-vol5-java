package com.loopers.application.payment;

import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.fixture.pg.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentReleaseCacheIntegrationTest extends PaymentHttpTestSupport {
    @Autowired private ProductFacade productFacade;
    @MockitoSpyBean private ProductDetailCache cache;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void releaseInvalidatesCacheOnlyAfterCommit(boolean commit) {
        productFacade.getProduct(product.getId());
        confirm(order);
        assertThat(cache.find(product.getId())).isEmpty();
        assertThat(productFacade.getProduct(product.getId()).stockQuantity()).isEqualTo(3);
        clearInvocations(cache);
        pg.setStatus(key(order), PaymentStatus.FAILED);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order, () -> {
            assertThat(stock(product.getId())).isEqualTo(5);
            assertThat(cache.find(product.getId()).orElseThrow().stockQuantity()).isEqualTo(3);
            verify(cache, never()).evictAll(List.of(product.getId()));
            if (!commit) { throw new IllegalStateException("반환 SQL 뒤 rollback"); }
        });
        assertThat(reconcile(order).getStatusCode().value()).isEqualTo(commit ? 200 : 500);
        if (commit) {
            verify(cache).evictAll(List.of(product.getId()));
            assertThat(cache.find(product.getId())).isEmpty();
            assertThat(productFacade.getProduct(product.getId()).stockQuantity()).isEqualTo(5);
            assertThat(histories(order, "ORDER_RELEASE")).isEqualTo(1);
        } else {
            verify(cache, never()).evictAll(List.of(product.getId()));
            assertThat(cache.find(product.getId()).orElseThrow().stockQuantity()).isEqualTo(3);
            assertThat(stock(product.getId())).isEqualTo(3);
            assertThat(histories(order, "ORDER_RELEASE")).isZero();
        }
    }
}
