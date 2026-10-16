package com.loopers.application.mall.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.loopers.application.mall.command.ProductCommand;
import com.loopers.application.mall.event.ProductDetailsChangedEvent;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class ProductServiceTest {
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final BrandRepository brandRepository = mock(BrandRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ProductService service = new ProductService(productRepository, brandRepository, eventPublisher);

    @DisplayName("상품 등록")
    @Nested
    class Create {
        private final ProductCommand.Create command = new ProductCommand.Create(1L, "상품", null, 1_000L, 5);

        @DisplayName("브랜드를 공유 잠금으로 읽고 상품을 저장한다")
        @Test
        void readsBrandWithShareLock_andSavesProduct() {
            given(brandRepository.findByIdForShare(1L))
                .willReturn(Optional.of(Brand.restore(1L, "브랜드", null, false, Instant.now())));
            given(productRepository.save(any(Product.class)))
                .willReturn(Product.restore(7L, 1L, "상품", null, 1_000L, 5, false, Instant.now()));

            long productId = service.execute(command);

            assertThat(productId).isEqualTo(7L);
            verify(brandRepository).findByIdForShare(1L);
            verify(brandRepository, never()).findById(anyLong());
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("삭제된 브랜드면 DELETED_BRAND로 거절하고 저장하지 않는다")
        @Test
        void rejectsCreate_whenBrandDeleted() {
            given(brandRepository.findByIdForShare(1L))
                .willReturn(Optional.of(Brand.restore(1L, "브랜드", null, true, Instant.now())));

            assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_BRAND);
            verify(productRepository, never()).save(any(Product.class));
        }

        @DisplayName("없는 브랜드면 BRAND_NOT_FOUND로 거절하고 저장하지 않는다")
        @Test
        void rejectsCreate_whenBrandNotFound() {
            given(brandRepository.findByIdForShare(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.BRAND_NOT_FOUND);
            verify(productRepository, never()).save(any(Product.class));
        }
    }

    @DisplayName("상품 상세 변경 이벤트")
    @Nested
    class DetailChanges {
        @DisplayName("상품 수정 저장 후 해당 상품의 변경 이벤트를 발행한다")
        @Test
        void publishesAfterUpdate() {
            // arrange
            Product product = activeProduct();
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));
            given(brandRepository.findById(1L)).willReturn(Optional.of(activeBrand()));
            given(productRepository.save(product)).willReturn(product);

            // act
            long result = service.execute(new ProductCommand.Update(10L, "새 상품", "새 설명", 2_000L));

            // assert
            assertThat(result).isEqualTo(10L);
            InOrder order = inOrder(productRepository, eventPublisher);
            order.verify(productRepository).save(product);
            order.verify(eventPublisher).publishEvent(new ProductDetailsChangedEvent(Set.of(10L)));
        }

        @DisplayName("재고 설정 저장 후 해당 상품의 변경 이벤트를 발행한다")
        @Test
        void publishesAfterSetStock() {
            // arrange
            Product product = activeProduct();
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));
            given(brandRepository.findById(1L)).willReturn(Optional.of(activeBrand()));
            given(productRepository.save(product)).willReturn(product);

            // act
            service.execute(new ProductCommand.SetStock(10L, 20));

            // assert
            InOrder order = inOrder(productRepository, eventPublisher);
            order.verify(productRepository).save(product);
            order.verify(eventPublisher).publishEvent(new ProductDetailsChangedEvent(Set.of(10L)));
        }

        @DisplayName("상품 삭제 저장 후 해당 상품의 변경 이벤트를 발행한다")
        @Test
        void publishesAfterDelete() {
            // arrange
            Product product = activeProduct();
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));

            // act
            service.execute(new ProductCommand.Delete(10L));

            // assert
            InOrder order = inOrder(productRepository, eventPublisher);
            order.verify(productRepository).save(product);
            order.verify(eventPublisher).publishEvent(new ProductDetailsChangedEvent(Set.of(10L)));
        }

        @DisplayName("상품 저장이 실패하면 변경 이벤트를 발행하지 않는다")
        @Test
        void doesNotPublish_whenSaveFails() {
            // arrange
            Product product = activeProduct();
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));
            given(productRepository.save(product)).willThrow(new IllegalStateException("저장 실패"));

            // act & assert
            assertThatThrownBy(() -> service.execute(new ProductCommand.Delete(10L)))
                .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(eventPublisher);
        }

        private Product activeProduct() {
            return Product.restore(10L, 1L, "상품", null, 1_000L, 5, false, Instant.now());
        }

        private Brand activeBrand() {
            return Brand.restore(1L, "브랜드", null, false, Instant.now());
        }
    }

    @DisplayName("재고 차감")
    @Nested
    class DecreaseStocks {
        @DisplayName("상품 id 오름차순으로 잠가 조회한 뒤 차감한 재고를 오름차순으로 저장한다")
        @Test
        void locksAndSavesInAscendingProductIdOrder() {
            Product productA = product(10L, 5);
            Product productB = product(20L, 5);
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(productA));
            given(productRepository.findByIdForUpdate(20L)).willReturn(Optional.of(productB));
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            quantities.put(20L, 1);
            quantities.put(10L, 2);

            service.decreaseStocks(quantities);

            assertThat(productA.getStock()).isEqualTo(3);
            assertThat(productB.getStock()).isEqualTo(4);
            InOrder inOrder = inOrder(productRepository, eventPublisher);
            inOrder.verify(productRepository).findByIdForUpdate(10L);
            inOrder.verify(productRepository).findByIdForUpdate(20L);
            inOrder.verify(productRepository).save(productA);
            inOrder.verify(productRepository).save(productB);
            inOrder.verify(eventPublisher).publishEvent(new ProductDetailsChangedEvent(Set.of(10L, 20L)));
        }

        @DisplayName("재고가 수량과 같으면 0까지 차감한다")
        @Test
        void decreasesToZero_whenStockEqualsQuantity() {
            Product product = product(10L, 5);
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));

            service.decreaseStocks(Map.of(10L, 5));

            assertThat(product.getStock()).isZero();
            verify(productRepository).save(product);
        }

        @DisplayName("없는 상품이면 PRODUCT_NOT_FOUND로 거절하고 저장하지 않는다")
        @Test
        void rejects_whenProductNotFound() {
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.decreaseStocks(Map.of(10L, 1)))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.PRODUCT_NOT_FOUND);
            verify(productRepository, never()).save(any(Product.class));
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("삭제된 상품이면 DELETED_PRODUCT로 거절하고 저장하지 않는다")
        @Test
        void rejects_whenProductDeleted() {
            Product product = product(10L, 5);
            product.delete();
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));

            assertThatThrownBy(() -> service.decreaseStocks(Map.of(10L, 1)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);
            verify(productRepository, never()).save(any(Product.class));
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("재고가 수량보다 1 부족하면 INSUFFICIENT_STOCK으로 거절하고 재고와 저장을 유지한다")
        @Test
        void rejectsStockShortByOne_andKeepsState() {
            Product product = product(10L, 4);
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(product));

            assertThatThrownBy(() -> service.decreaseStocks(Map.of(10L, 5)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(product.getStock()).isEqualTo(4);
            verify(productRepository, never()).save(any(Product.class));
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("뒤쪽 품목이 실패하면 앞쪽 품목의 재고도 차감되지 않고 저장하지 않는다")
        @Test
        void rejectsWhenLaterItemFails_keepingEarlierItemStockUnchanged() {
            Product productA = product(10L, 5);
            Product productB = product(20L, 4);
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(productA));
            given(productRepository.findByIdForUpdate(20L)).willReturn(Optional.of(productB));
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            quantities.put(10L, 1);
            quantities.put(20L, 5);

            assertThatThrownBy(() -> service.decreaseStocks(quantities))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
            assertThat(productA.getStock()).isEqualTo(5);
            assertThat(productB.getStock()).isEqualTo(4);
            verify(productRepository, never()).save(any(Product.class));
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("여러 품목이 실패하면 품목 등장 순서상 먼저인 오류를 낸다")
        @Test
        void reportsFirstFailureInItemAppearanceOrder() {
            Product deleted = product(10L, 5);
            deleted.delete();
            Product shortStock = product(20L, 1);
            given(productRepository.findByIdForUpdate(10L)).willReturn(Optional.of(deleted));
            given(productRepository.findByIdForUpdate(20L)).willReturn(Optional.of(shortStock));
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            quantities.put(20L, 5);
            quantities.put(10L, 1);

            assertThatThrownBy(() -> service.decreaseStocks(quantities))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);
        }

        private Product product(long id, int stock) {
            return Product.restore(id, 1L, "상품", null, 1_000L, stock, false, Instant.now());
        }
    }
}
