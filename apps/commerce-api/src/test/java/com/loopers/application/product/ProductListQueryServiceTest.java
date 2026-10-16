package com.loopers.application.product;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductListQueryServiceTest {
    @Mock private ProductQueryService products;
    @Mock private UserService users;
    @Mock private LikeService likes;

    private final CustomerProductInfo first = new CustomerProductInfo(10L, null, "first", 1000, 5, null);
    private final CustomerProductInfo second = new CustomerProductInfo(20L, null, "second", 2000, 3, null);

    @ParameterizedTest
    @EnumSource(ProductSort.class)
    void personalizesOnlyTheSelectedPageAndPreservesOrder(ProductSort sort) {
        int page = sort == ProductSort.POPULAR ? 0 : 2;
        int size = sort == ProductSort.POPULAR ? 20 : 2;
        when(products.getList(1L, page, size, sort)).thenReturn(new ProductPage<>(List.of(second, first), page, size));
        when(likes.findLikedProductIds(7L, List.of(20L, 10L))).thenReturn(Set.of(10L));
        var result = new ProductListQueryService(products, users, likes).getList(7L, 1L, page, size, sort);
        assertThat(result.items()).extracting(item -> item.product().id()).containsExactly(20L, 10L);
        assertThat(result.items()).extracting(ProductListItemInfo::isLiked).containsExactly(false, true);
        assertThat(result.items()).extracting(item -> item.product().likeCount()).containsExactly(3L, 5L);
        assertThat(result.page()).isEqualTo(page);
        assertThat(result.size()).isEqualTo(size);
        verify(users).get(7L);
        verify(likes).findLikedProductIds(7L, List.of(20L, 10L));
    }

    @Test
    void anonymousListDoesNotReadUsersOrPersonalRelationships() {
        when(products.getList(null, 0, 20, ProductSort.PRICE_ASC))
            .thenReturn(new ProductPage<>(List.of(first, second), 0, 20));
        var result = new ProductListQueryService(products, users, likes)
            .getList(null, null, 0, 20, ProductSort.PRICE_ASC);
        assertThat(result.items()).extracting(ProductListItemInfo::isLiked).containsExactly(false, false);
        verifyNoInteractions(users, likes);
    }

    @Test
    void missingRequesterIsRejectedBeforeReadingTheProductPage() {
        doThrow(new CoreException(ErrorType.NOT_FOUND)).when(users).get(7L);
        assertThatThrownBy(() -> new ProductListQueryService(products, users, likes)
            .getList(7L, 1L, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(CoreException.class);
        verifyNoInteractions(products, likes);
    }

    @Test
    void personalReadFailureIsNotReportedAsUnliked() {
        when(products.getList(1L, 0, 20, ProductSort.PRICE_ASC))
            .thenReturn(new ProductPage<>(List.of(first), 0, 20));
        doThrow(new DataAccessResourceFailureException("personal relationship failure"))
            .when(likes).findLikedProductIds(7L, List.of(10L));
        assertThatThrownBy(() -> new ProductListQueryService(products, users, likes)
            .getList(7L, 1L, 0, 20, ProductSort.PRICE_ASC))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
