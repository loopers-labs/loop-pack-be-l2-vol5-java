package com.loopers.domain.brand;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandServiceTest {
    @Mock
    private BrandRepository brandRepository;

    @Test
    void createsBrandAfterActiveNameCheck() {
        when(brandRepository.existsActiveByNameIgnoreCase("Nike")).thenReturn(false);
        when(brandRepository.save(any(BrandModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BrandModel result = new BrandService(brandRepository).create(" Nike ", "설명");

        assertThat(result.getName()).isEqualTo("Nike");
        verify(brandRepository).save(result);
    }

    @Test
    void rejectsDuplicateActiveNameWithoutSaving() {
        when(brandRepository.existsActiveByNameIgnoreCase("NIKE")).thenReturn(true);

        assertThatThrownBy(() -> new BrandService(brandRepository).create("NIKE", null))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);

        verify(brandRepository, never()).save(any());
    }

    @Test
    void returnsOnlyActiveBrand() {
        BrandModel brand = new BrandModel("Nike", null);
        when(brandRepository.find(1L)).thenReturn(Optional.of(brand));

        assertThat(new BrandService(brandRepository).get(1L)).isSameAs(brand);
    }

    @Test
    void rejectsMissingBrand() {
        when(brandRepository.find(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new BrandService(brandRepository).get(1L))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
    }

    @Test
    void deletesActiveBrand() {
        BrandModel brand = new BrandModel("Nike", null);
        when(brandRepository.findForUpdate(1L)).thenReturn(Optional.of(brand));

        new BrandService(brandRepository).delete(1L);

        assertThat(brand.getDeletedAt()).isNotNull();
        verify(brandRepository).save(brand);
    }
}
