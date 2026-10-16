package com.loopers.infrastructure.query.mall;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.loopers.application.mall.event.AllProductDetailsChangedEvent;
import com.loopers.application.mall.event.BrandProductsChangedEvent;
import com.loopers.application.mall.event.ProductDetailsChangedEvent;
import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ProductDetailCacheInvalidationListenerTest {
    private final ProductDetailCache cache = mock(ProductDetailCache.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ListenerConfiguration.class, ProductDetailCacheInvalidationListener.class)
        .withBean(ProductDetailCache.class, () -> cache);

    @DisplayName("상품·브랜드·전체·좋아요 이벤트는 커밋한 뒤 같은 스레드에서 무효화한다")
    @Test
    void invalidatesOnlyAfterSuccessfulCommit() {
        // arrange
        contextRunner.run(context -> {
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            // act
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new ProductDetailsChangedEvent(Set.of(1L, 2L)));
                context.publishEvent(new BrandProductsChangedEvent(7));
                context.publishEvent(new AllProductDetailsChangedEvent());
                context.publishEvent(new ProductLikeChangedEvent(3, -1));
                verifyNoInteractions(cache);
            });

            // assert
            verify(cache).invalidateProducts(Set.of(1L, 2L));
            verify(cache).invalidateBrand(7);
            verify(cache).invalidateAll();
            verify(cache).invalidateProducts(Set.of(3L));
            verifyNoMoreInteractions(cache);
        });
    }

    @DisplayName("롤백된 이벤트는 캐시를 무효화하지 않는다")
    @Test
    void skipsRolledBackEvents() {
        // arrange
        contextRunner.run(context -> {
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            // act
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new ProductDetailsChangedEvent(Set.of(1L)));
                context.publishEvent(new BrandProductsChangedEvent(7));
                context.publishEvent(new AllProductDetailsChangedEvent());
                context.publishEvent(new ProductLikeChangedEvent(1, 1));
                status.setRollbackOnly();
            });

            // assert
            verifyNoInteractions(cache);
        });
    }

    @DisplayName("트랜잭션 밖의 이벤트는 fallback 없이 무시한다")
    @Test
    void skipsEventsWithoutTransaction() {
        // arrange, act
        contextRunner.run(context -> {
            context.publishEvent(new ProductDetailsChangedEvent(Set.of(1L)));
            context.publishEvent(new BrandProductsChangedEvent(7));
            context.publishEvent(new AllProductDetailsChangedEvent());
            context.publishEvent(new ProductLikeChangedEvent(1, 1));

            // assert
            verifyNoInteractions(cache);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class ListenerConfiguration {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                @Override
                protected void doCommit(DefaultTransactionStatus status) {}

                @Override
                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }
}
