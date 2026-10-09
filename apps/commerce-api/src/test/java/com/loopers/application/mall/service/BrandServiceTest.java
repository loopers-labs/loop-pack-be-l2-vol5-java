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

import com.loopers.application.mall.command.BrandCommand;
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

class BrandServiceTest {

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
            BrandService service = new BrandService(brandRepository, productRepository);

            // act
            service.execute(new BrandCommand.Delete(1L));

            // assert
            InOrder inOrder = inOrder(brandRepository, productRepository);
            inOrder.verify(brandRepository).findByIdForUpdate(1L);
            ArgumentCaptor<Brand> saved = ArgumentCaptor.forClass(Brand.class);
            inOrder.verify(brandRepository).save(saved.capture());
            inOrder.verify(productRepository).deleteAllByBrandId(1L);
            assertThat(saved.getValue().isDeleted()).isTrue();
        }

        @DisplayName("없는 브랜드는 BRAND_NOT_FOUND이고 저장도 상품 삭제도 하지 않는다")
        @Test
        void rejectsDelete_whenBrandNotFound() {
            BrandRepository brandRepository = mock(BrandRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            given(brandRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());
            BrandService service = new BrandService(brandRepository, productRepository);

            assertThatThrownBy(() -> service.execute(new BrandCommand.Delete(1L)))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ApplicationErrorCode.BRAND_NOT_FOUND);
            verify(brandRepository, never()).save(any(Brand.class));
            verify(productRepository, never()).deleteAllByBrandId(anyLong());
        }

        @DisplayName("이미 삭제된 브랜드는 DELETED_BRAND이고 저장도 상품 삭제도 하지 않는다")
        @Test
        void rejectsDelete_whenBrandAlreadyDeleted() {
            BrandRepository brandRepository = mock(BrandRepository.class);
            ProductRepository productRepository = mock(ProductRepository.class);
            given(brandRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(Brand.restore(1L, "브랜드", null, true, Instant.now())));
            BrandService service = new BrandService(brandRepository, productRepository);

            assertThatThrownBy(() -> service.execute(new BrandCommand.Delete(1L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_BRAND);
            verify(brandRepository, never()).save(any(Brand.class));
            verify(productRepository, never()).deleteAllByBrandId(anyLong());
        }
    }
}
