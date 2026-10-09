package com.loopers.application.brand;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandFacadeTest {

    @Mock
    private BrandService brandService;

    @Mock
    private ProductRepository productRepository;

    @Test
    void deletesBrandWhenThereAreNoActiveProducts() {
        when(productRepository.findActiveByBrandIdForUpdate(1L)).thenReturn(List.of());

        new BrandFacade(brandService, productRepository).delete(1L);

        verify(brandService).delete(1L);
    }

    @Test
    void deletesBrandAndItsActiveProducts() {
        ProductModel product = new ProductModel(1L, "상품", 1000, 0);
        when(productRepository.findActiveByBrandIdForUpdate(1L)).thenReturn(List.of(product));

        new BrandFacade(brandService, productRepository).delete(1L);

        verify(brandService).delete(1L);
        verify(productRepository).save(product);
        assertThat(product.getDeletedAt()).isNotNull();
    }
}
