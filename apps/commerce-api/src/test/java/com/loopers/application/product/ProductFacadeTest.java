package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductFacadeTest {

    @Mock
    private ProductService productService;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private LikeService likeService;

    @InjectMocks
    private ProductFacade productFacade;

    @DisplayName("고객이 상품 목록을 조회할 때, ")
    @Nested
    class GetProductsAsCustomer {
        @DisplayName("상품에 브랜드 정보와 좋아요 수를 조합해 반환한다.")
        @Test
        void combinesBrandAndLikeCount() {
            // arrange
            given(productService.getProducts(null, "latest", 0, 20))
                .willReturn(List.of(new Product(1L, "루퍼스 티셔츠", new Price(1000L))));
            given(brandRepository.findById(1L)).willReturn(Optional.of(new Brand("루퍼스")));
            given(likeService.countByProductIds(anyList())).willReturn(Map.of(0L, 5L));

            // act
            List<ProductInfo> result = productFacade.getProducts(null, "latest", 0, 20);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).brandName()).isEqualTo("루퍼스");
            assertThat(result.get(0).likeCount()).isEqualTo(5L);
        }

        @DisplayName("상품 조회와 좋아요 수 조회에 각각 위임한다.")
        @Test
        void delegatesToProductServiceAndLikeService() {
            // arrange
            given(productService.getProducts(1L, "price_asc", 0, 20)).willReturn(List.of());

            // act
            productFacade.getProducts(1L, "price_asc", 0, 20);

            // assert
            verify(productService).getProducts(1L, "price_asc", 0, 20);
        }

        @DisplayName("지원하지 않는 정렬값이면, BAD_REQUEST 예외가 전파된다.")
        @Test
        void propagatesBadRequestException_whenSortIsNotSupported() {
            // arrange
            given(productService.getProducts(null, "price_desc", 0, 20))
                .willThrow(new CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 정렬 조건입니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.getProducts(null, "price_desc", 0, 20);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("고객이 상품 상세를 조회할 때, ")
    @Nested
    class GetProductAsCustomer {
        @DisplayName("상품에 브랜드 정보와 좋아요 수를 조합해 반환한다.")
        @Test
        void combinesBrandAndLikeCount() {
            // arrange
            given(productService.getActiveProduct(1L))
                .willReturn(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));
            given(brandRepository.findById(1L)).willReturn(Optional.of(new Brand("루퍼스")));
            given(likeService.countByProductId(0L)).willReturn(7L);

            // act
            ProductInfo result = productFacade.getProduct(1L);

            // assert
            assertThat(result.productName()).isEqualTo("루퍼스 티셔츠");
            assertThat(result.brandName()).isEqualTo("루퍼스");
            assertThat(result.likeCount()).isEqualTo(7L);
        }

        @DisplayName("없거나 삭제된 상품이면, NOT_FOUND 예외가 전파된다.")
        @Test
        void propagatesNotFoundException_whenProductIsAbsentOrDeleted() {
            // arrange
            given(productService.getActiveProduct(1L))
                .willThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.getProduct(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("관리자가 상품을 등록할 때, ")
    @Nested
    class CreateProductAsAdmin {
        @DisplayName("브랜드가 존재하고 삭제되지 않았으면, 상품 등록에 위임한다.")
        @Test
        void delegatesToProductService_whenBrandExistsAndIsNotDeleted() {
            // arrange
            given(brandRepository.findById(1L)).willReturn(Optional.of(new Brand("루퍼스")));
            given(productService.createProduct(eq(1L), eq("루퍼스 티셔츠"), any(Price.class)))
                .willReturn(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            ProductInfo result = productFacade.createProduct(1L, "루퍼스 티셔츠", 1000L);

            // assert
            assertThat(result.productName()).isEqualTo("루퍼스 티셔츠");
            assertThat(result.brandName()).isEqualTo("루퍼스");
            verify(productService).createProduct(eq(1L), eq("루퍼스 티셔츠"), any(Price.class));
        }

        @DisplayName("브랜드가 없으면, NOT_FOUND 예외가 발생하고 상품은 등록되지 않는다.")
        @Test
        void doesNotCreateProduct_whenBrandIsAbsent() {
            // arrange
            given(brandRepository.findById(1L)).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.createProduct(1L, "루퍼스 티셔츠", 1000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(productService, never()).createProduct(anyLong(), anyString(), any(Price.class));
        }

        @DisplayName("브랜드가 삭제되었으면, NOT_FOUND 예외가 발생하고 상품은 등록되지 않는다.")
        @Test
        void doesNotCreateProduct_whenBrandIsDeleted() {
            // arrange
            Brand deleted = new Brand("루퍼스");
            deleted.delete();
            given(brandRepository.findById(1L)).willReturn(Optional.of(deleted));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.createProduct(1L, "루퍼스 티셔츠", 1000L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(productService, never()).createProduct(anyLong(), anyString(), any(Price.class));
        }
    }

    @DisplayName("관리자가 상품을 삭제할 때, ")
    @Nested
    class DeleteProductAsAdmin {
        @DisplayName("상품 삭제를 서비스에 위임한다.")
        @Test
        void delegatesToProductService() {
            // act
            productFacade.deleteProduct(1L);

            // assert
            verify(productService).deleteProduct(1L);
        }
    }

    @DisplayName("관리자가 상품 재고를 변경할 때, ")
    @Nested
    class ChangeStockAsAdmin {
        @DisplayName("재고 변경을 상품 서비스에 위임하고, 변경된 재고를 반환한다.")
        @Test
        void delegatesToProductService() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            product.changeStock(7);
            given(productService.changeStock(1L, 7)).willReturn(product);

            // act
            int result = productFacade.changeStock(1L, 7);

            // assert
            assertThat(result).isEqualTo(7);
            verify(productService).changeStock(1L, 7);
        }

        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 전파된다.")
        @Test
        void propagatesNotFoundException_whenProductIsAbsent() {
            // arrange
            given(productService.changeStock(1L, 7))
                .willThrow(new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productFacade.changeStock(1L, 7);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
