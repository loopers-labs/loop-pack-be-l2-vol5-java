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

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.event.BrandProductsChangedEvent;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class BrandServiceTest {
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    @DisplayName("브랜드 수정 저장 후 해당 브랜드 상품 변경 이벤트를 발행한다")
    @Test
    void publishesAfterUpdate() {
        // arrange
        BrandRepository brandRepository = mock(BrandRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        Brand brand = Brand.restore(1L, "브랜드", null, false, Instant.now());
        given(brandRepository.findById(1L)).willReturn(Optional.of(brand));
        given(brandRepository.save(brand)).willReturn(brand);
        BrandService service = new BrandService(brandRepository, productRepository, eventPublisher);

        // act
        service.execute(new BrandCommand.Update(1L, "새 브랜드", "새 설명"));

        // assert
        InOrder order = inOrder(brandRepository, eventPublisher);
        order.verify(brandRepository).save(brand);
        order.verify(eventPublisher).publishEvent(new BrandProductsChangedEvent(1L));
    }

    @DisplayName("브랜드 수정 저장이 실패하면 변경 이벤트를 발행하지 않는다")
    @Test
    void doesNotPublish_whenUpdateSaveFails() {
        BrandRepository brandRepository = mock(BrandRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        Brand brand = Brand.restore(1L, "브랜드", null, false, Instant.now());
        given(brandRepository.findById(1L)).willReturn(Optional.of(brand));
        given(brandRepository.save(brand)).willThrow(new IllegalStateException("저장 실패"));
        BrandService service = new BrandService(brandRepository, productRepository, eventPublisher);

        assertThatThrownBy(() -> service.execute(new BrandCommand.Update(1L, "새 브랜드", null)))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(eventPublisher);
    }

    @DisplayName("브랜드 삭제")
    @Nested
    class Delete {
        @DisplayName("쓰기 잠금으로 조회한 브랜드를 저장한 뒤 상품을 일괄 삭제한다")
        @Test
        void savesBrand_thenDeletesProductsInBulk() {
            // arrange
            BrandRepository brandRepository = mock(BrandRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            given(brandRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(Brand.restore(1L, "브랜드", null, false, Instant.now())));
            BrandService service = new BrandService(brandRepository, productRepository, eventPublisher);

            // act
            service.execute(new BrandCommand.Delete(1L));

            // assert
            InOrder inOrder = inOrder(brandRepository, productRepository, eventPublisher);
            inOrder.verify(brandRepository).findByIdForUpdate(1L);
            ArgumentCaptor<Brand> saved = ArgumentCaptor.forClass(Brand.class);
            inOrder.verify(brandRepository).save(saved.capture());
            inOrder.verify(productRepository).deleteAllByBrandId(1L);
            inOrder.verify(eventPublisher).publishEvent(new BrandProductsChangedEvent(1L));
            assertThat(saved.getValue().isDeleted()).isTrue();
        }

        @DisplayName("없는 브랜드는 BRAND_NOT_FOUND이고 저장도 상품 삭제도 하지 않는다")
        @Test
        void rejectsDelete_whenBrandNotFound() {
            BrandRepository brandRepository = mock(BrandRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            given(brandRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());
            BrandService service = new BrandService(brandRepository, productRepository, eventPublisher);

            assertThatThrownBy(() -> service.execute(new BrandCommand.Delete(1L)))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.BRAND_NOT_FOUND);
            verify(brandRepository, never()).save(any(Brand.class));
            verify(productRepository, never()).deleteAllByBrandId(anyLong());
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("이미 삭제된 브랜드는 DELETED_BRAND이고 저장도 상품 삭제도 하지 않는다")
        @Test
        void rejectsDelete_whenBrandAlreadyDeleted() {
            BrandRepository brandRepository = mock(BrandRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            given(brandRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(Brand.restore(1L, "브랜드", null, true, Instant.now())));
            BrandService service = new BrandService(brandRepository, productRepository, eventPublisher);

            assertThatThrownBy(() -> service.execute(new BrandCommand.Delete(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_BRAND);
            verify(brandRepository, never()).save(any(Brand.class));
            verify(productRepository, never()).deleteAllByBrandId(anyLong());
            verifyNoInteractions(eventPublisher);
        }
    }
}
