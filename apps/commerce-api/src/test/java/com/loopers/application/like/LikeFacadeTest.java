package com.loopers.application.like;

import com.loopers.application.product.query.ProductView;
import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeFacadeTest {
    @Test
    @DisplayName("등록하면 요청자와 상품의 관계가 생긴다")
    void registersRelation() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        facade.create(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(1, 10));
    }

    @Test
    @DisplayName("등록된 관계의 재요청은 관계를 추가하지 않는다")
    void repeatedRegistrationKeepsOneRelation() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);
        likes.save(new ProductLike(1, 10));

        // act
        facade.create(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(1, 10));
    }

    @Test
    @DisplayName("취소하면 본인 관계만 제거한다")
    void cancelOnlyRemovesOwnRelation() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);
        likes.save(new ProductLike(1, 10));
        likes.save(new ProductLike(2, 10));

        // act
        facade.delete(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(2, 10));
    }

    @Test
    @DisplayName("본인 관계가 없어도 다른 사용자의 관계를 유지한다")
    void absentRelationCanBeCancelled() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);
        likes.save(new ProductLike(2, 10));

        // act
        facade.delete(1L, 10);

        // assert
        assertThat(likes.values).containsExactly(new ProductLike(2, 10));
    }

    @Test
    @DisplayName("삭제된 상품은 기존 관계가 있어도 등록을 거절한다")
    void deletedProductRejectsRegistration() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);
        likes.save(new ProductLike(1, 10));
        products.values.get(10L).delete();

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(1L, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(likes.values).containsExactly(new ProductLike(1, 10));
    }

    @Test
    @DisplayName("취소는 상품 조회 없이 저장된 본인 관계를 제거한다")
    void cancelsWithoutProductLookup() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        DeleteLikeFacade facade = new DeleteLikeFacade(users, likes);
        likes.save(new ProductLike(1, 10));

        // act
        facade.delete(1L, 10);

        // assert
        assertThat(likes.values).isEmpty();
    }

    @Test
    @DisplayName("없는 상품은 등록할 수 없다")
    void rejectsMissingProduct() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(1L, 999));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(likes.values).isEmpty();
    }

    @Test
    @DisplayName("없는 사용자는 등록할 수 없다")
    void rejectsMissingUser() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(999L, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.USER_NOT_FOUND);
        assertThat(likes.values).isEmpty();
    }

    @Test
    @DisplayName("식별 누락은 등록할 수 없다")
    void rejectsMissingIdentity() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        FakeLikes likes = new FakeLikes();
        FakeProducts products = new FakeProducts();
        products.values.put(10L, Product.restore(10, 1, "상품", 1_000, 0, false));
        CreateLikeFacade facade = new CreateLikeFacade(users, likes, products);

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.create(null, 10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(likes.values).isEmpty();
    }

    @Test
    @DisplayName("본인의 좋아요 목록을 반환한다")
    void returnsOwnList() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        ProductView expected = new ProductView(10, "상품", 1_000, new ProductView.BrandView(1, "브랜드"), 1);
        GetLikeFacade facade = new GetLikeFacade(users, id -> id == 1 ? List.of(expected) : List.of());

        // act
        var result = facade.get(1L, 1);

        // assert
        assertThat(result).containsExactly(expected);
    }

    @Test
    @DisplayName("본인 좋아요가 없으면 빈 목록을 반환한다")
    void returnsEmptyOwnList() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1);
        GetLikeFacade facade = new GetLikeFacade(users, id -> List.of());

        // act
        var result = facade.get(1L, 1);

        // assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("타인 목록 접근은 권한 오류로 거절한다")
    void rejectsForeignList() {
        // arrange
        IdentifyUser users = new IdentifyUser(id -> id == 1 || id == 2);
        GetLikeFacade facade = new GetLikeFacade(users, id -> List.of());

        // act
        CoreException error = assertThrows(CoreException.class, () -> facade.get(1L, 2));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.ACCESS_DENIED);
    }
    private static class FakeLikes implements ProductLikeRepository {
        private final Set<ProductLike> values = new HashSet<>();

        @Override
        public boolean exists(long userId, long productId) {
            return values.contains(new ProductLike(userId, productId));
        }

        @Override
        public void save(ProductLike like) {
            if (!values.add(like)) {
                throw new IllegalStateException("duplicate relation");
            }
        }

        @Override
        public void delete(long userId, long productId) {
            values.remove(new ProductLike(userId, productId));
        }
    }

    private static class FakeProducts implements ProductRepository {
        private final Map<Long, Product> values = new HashMap<>();

        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public Product save(Product product) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<Product> findAll(Pageable pageable) {
            throw new UnsupportedOperationException();
        }
    }
}
