package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BrandFacadeTest {

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private BrandFacade brandFacade;

    @DisplayName("브랜드를 생성할 때, ")
    @Nested
    class CreateBrand {
        @DisplayName("이름이 유효하면, 저장에 위임하고 생성된 브랜드를 반환한다.")
        @Test
        void delegatesToRepository_whenNameIsValid() {
            // arrange
            given(brandRepository.save(any(Brand.class))).willAnswer(invocation -> invocation.getArgument(0));

            // act
            Brand result = brandFacade.createBrand("루퍼스");

            // assert
            assertThat(result.getName()).isEqualTo("루퍼스");
            verify(brandRepository).save(any(Brand.class));
        }

        @DisplayName("이름이 비어있으면, BAD_REQUEST 예외가 발생하고 저장하지 않는다.")
        @Test
        void doesNotSave_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.createBrand("   ");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(brandRepository, never()).save(any(Brand.class));
        }
    }

    @DisplayName("브랜드를 단건 조회할 때, ")
    @Nested
    class GetBrand {
        @DisplayName("존재하고 삭제되지 않았으면, 브랜드 정보를 반환한다.")
        @Test
        void returnsBrand_whenBrandExistsAndIsNotDeleted() {
            // arrange
            given(brandRepository.findById(1L)).willReturn(Optional.of(new Brand("루퍼스")));

            // act
            Brand result = brandFacade.getBrand(1L);

            // assert
            assertThat(result.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("존재하지 않으면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenBrandDoesNotExist() {
            // arrange
            given(brandRepository.findById(1L)).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.getBrand(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenBrandIsDeleted() {
            // arrange
            Brand deleted = new Brand("루퍼스");
            deleted.delete();
            given(brandRepository.findById(1L)).willReturn(Optional.of(deleted));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.getBrand(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("브랜드를 수정할 때, ")
    @Nested
    class UpdateBrand {
        @DisplayName("유효한 이름이면, 해당 이름으로 변경된다.")
        @Test
        void changesName_whenNameIsValid() {
            // arrange
            given(brandRepository.findById(1L)).willReturn(Optional.of(new Brand("루퍼스")));

            // act
            Brand result = brandFacade.updateBrand(1L, "새 이름");

            // assert
            assertThat(result.getName()).isEqualTo("새 이름");
        }

        @DisplayName("삭제된 브랜드이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenBrandIsDeleted() {
            // arrange
            Brand deleted = new Brand("루퍼스");
            deleted.delete();
            given(brandRepository.findById(1L)).willReturn(Optional.of(deleted));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.updateBrand(1L, "새 이름");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("브랜드를 삭제할 때, ")
    @Nested
    class DeleteBrand {
        @DisplayName("연결된 상품이 없으면, 논리 삭제된다.")
        @Test
        void deletesBrand_whenNoActiveProductRemains() {
            // arrange
            Brand brand = new Brand("루퍼스");
            given(brandRepository.findById(1L)).willReturn(Optional.of(brand));
            given(productRepository.existsActiveByBrandId(1L)).willReturn(false);

            // act
            brandFacade.deleteBrand(1L);

            // assert
            assertThat(brand.getDeletedAt()).isNotNull();
            assertThat(brand.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("삭제되지 않은 연결 상품이 남아 있으면, CONFLICT 예외가 발생하고 삭제되지 않는다.")
        @Test
        void throwsConflictException_whenActiveProductRemains() {
            // arrange
            Brand brand = new Brand("루퍼스");
            given(brandRepository.findById(1L)).willReturn(Optional.of(brand));
            given(productRepository.existsActiveByBrandId(1L)).willReturn(true);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.deleteBrand(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
            assertThat(brand.getDeletedAt()).isNull();
        }

        @DisplayName("이미 삭제된 브랜드이면, NOT_FOUND 예외가 발생하고 연결 상품을 확인하지 않는다.")
        @Test
        void throwsNotFoundException_whenAlreadyDeleted() {
            // arrange
            Brand deleted = new Brand("루퍼스");
            deleted.delete();
            given(brandRepository.findById(1L)).willReturn(Optional.of(deleted));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.deleteBrand(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            verify(productRepository, never()).existsActiveByBrandId(1L);
        }
    }
}
