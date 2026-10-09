package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Test
    void createsProduct() {
        when(productRepository.save(any(ProductModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductModel result = new ProductService(productRepository)
            .create(1L, "운동화", 100_000L, 10);

        verify(productRepository).save(result);
    }

    @Test
    void returnsOnlyActiveProduct() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 10);
        when(productRepository.find(1L)).thenReturn(Optional.of(product));

        assertThat(new ProductService(productRepository).get(1L)).isSameAs(product);
    }

    @Test
    void rejectsMissingProduct() {
        when(productRepository.find(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ProductService(productRepository).get(1L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
    }

    @Test
    void changesStockOfActiveProduct() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 10);
        when(productRepository.findForUpdate(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);

        ProductModel result = new ProductService(productRepository).changeStock(1L, 3);

        assertThat(result.getStockQuantity()).isEqualTo(3);
        verify(productRepository).save(product);
    }

    @Test
    void updatesActiveProduct() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 10);
        when(productRepository.findForUpdate(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);

        ProductModel result = new ProductService(productRepository).update(1L, "러닝화", 120_000L);

        assertThat(result.getName()).isEqualTo("러닝화");
        assertThat(result.getPrice()).isEqualTo(120_000L);
        assertThat(result.getBrandId()).isEqualTo(1L);
    }

    @Test
    void deletesActiveProductWhileKeepingStock() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 10);
        when(productRepository.findForUpdate(1L)).thenReturn(Optional.of(product));

        new ProductService(productRepository).delete(1L);

        assertThat(product.getDeletedAt()).isNotNull();
        assertThat(product.getStockQuantity()).isEqualTo(10);
        verify(productRepository).save(product);
    }

    @Test
    void rejectsRepeatedDeleteOfProduct() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 10);
        product.markDeleted();
        when(productRepository.findForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> new ProductService(productRepository).delete(1L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);

        verify(productRepository, never()).save(product);
    }
}
