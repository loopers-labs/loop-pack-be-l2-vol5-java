package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class BrandFacadeIntegrationTest {

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Brand를 등록할 때,")
    @Nested
    class Register {
        @DisplayName("등록되지 않은 이름이면, Brand를 저장하고 반환한다.")
        @Test
        void registersBrand_whenNameIsNotRegistered() {
            // act
            BrandInfo result = brandFacade.register("Nike");

            // assert
            brandJpaRepository.flush();
            entityManager.clear();
            Brand savedBrand = brandJpaRepository.findById(result.id()).orElseThrow();
            assertAll(
                () -> assertThat(result.name()).isEqualTo("Nike"),
                () -> assertThat(savedBrand.getName()).isEqualTo("Nike")
            );
        }

        @DisplayName("삭제된 Brand와 같은 이름이면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsException_whenNameMatchesDeletedBrand() {
            // arrange
            Brand deletedBrand = brandJpaRepository.save(new Brand("Nike"));
            deletedBrand.delete();
            brandJpaRepository.save(deletedBrand);
            brandJpaRepository.flush();
            entityManager.clear();

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brandFacade.register("Nike");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }
}
