package com.loopers.application.product;

import com.loopers.domain.brand.BrandService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.like.LikeService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductFacadeTest {

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private BrandService brandService;

    @Mock
    private ProductService productService;

    @Mock
    private LikeService likeService;

    @Test
    void checksActiveBrandBeforeCreatingProduct() {
        ProductModel product = new ProductModel(1L, "운동화", 100_000L, 10);
        ReflectionTestUtils.setField(product, "id", 10L);
        when(productService.create(1L, "운동화", 100_000L, 10)).thenReturn(product);

        ProductInfo result = new ProductFacade(brandService, productService, likeService, events)
            .create(1L, "운동화", 100_000L, 10);

        InOrder inOrder = inOrder(brandService, productService, likeService);
        inOrder.verify(brandService).getForShare(1L);
        inOrder.verify(productService).create(1L, "운동화", 100_000L, 10);
        inOrder.verify(likeService).initializeCount(10L);
        assertThat(result).isEqualTo(ProductInfo.from(product));
    }
}
