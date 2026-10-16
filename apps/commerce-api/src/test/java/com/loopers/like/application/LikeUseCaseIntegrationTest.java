package com.loopers.like.application;

import com.loopers.like.domain.Like;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.product.domain.Product;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class LikeUseCaseIntegrationTest {
    @Autowired private LikeUseCase useCase;
    @Autowired private EntityManager entityManager;

    @DisplayName("[INV-LIKE-24] 한 고객은 한 상품에 좋아요를 하나만 갖는다.")
    @Nested class PreventDuplicateLike {
        @DisplayName("[상태 전이] 이미 관계가 있으면 새 관계를 만들지 않아 관계 수가 1이다.")
        @Test void doesNotSaveDuplicate() {
            Scenario scenario = scenario();
            persist(new Like(scenario.user().getId(), scenario.product().getId()));
            useCase.register(scenario.user().getId(), scenario.product().getId());
            assertThat(countLikes(scenario.product().getId())).isEqualTo(1L);
        }
    }

    @DisplayName("[INV-LIKE-45] 상품의 좋아요 수는 그 상품의 좋아요 관계 개수와 같다.")
    @Nested class ReflectLikeCount {
        @DisplayName("[상태 전이] 새 좋아요 등록 전후 집계값은 0에서 1이 된다.")
        @Test void registersAndReadsCount() {
            Scenario scenario = scenario();
            assertThat(countLikes(scenario.product().getId())).isZero();
            useCase.register(scenario.user().getId(), scenario.product().getId());
            assertThat(useCase.count(scenario.product().getId())).isEqualTo(1L);
        }
    }

    @DisplayName("[INV-LIKE-26] 상품이 삭제되어도 기존 좋아요는 남는다.")
    @Nested class CancelAfterProductDeletion {
        @DisplayName("[상태 전이] 상품 삭제 뒤 남은 자신의 관계를 취소하면 관계 수가 0이다.")
        @Test void cancelsRemainingRelation() {
            Scenario scenario = scenario();
            persist(new Like(scenario.user().getId(), scenario.product().getId()));
            scenario.product().delete();
            entityManager.flush();
            assertThat(countRows(scenario.product().getId())).isEqualTo(1L);
            useCase.cancel(scenario.user().getId(), scenario.product().getId());
            entityManager.flush();
            entityManager.clear();
            assertAll(
                () -> assertThat(countRows(scenario.product().getId())).isZero(),
                () -> assertThat(countLikes(scenario.product().getId())).isZero()
            );
        }
    }

    @DisplayName("[INV-LIKE-24] 한 고객은 한 상품에 좋아요를 하나만 갖는다.")
    @Nested class IdempotentRequests {
        @DisplayName("[상태 전이] 같은 상품을 두 번 등록해도 관계 수는 1이다.")
        @Test void ignoresRepeatedRegistration() {
            Scenario scenario = scenario();
            useCase.register(scenario.user().getId(), scenario.product().getId());
            useCase.register(scenario.user().getId(), scenario.product().getId());
            assertThat(countLikes(scenario.product().getId())).isEqualTo(1L);
        }

        @DisplayName("[상태 전이] 관계가 없는 취소는 성공하고 관계 수 0을 유지한다.")
        @Test void ignoresMissingLikeOnCancel() {
            Scenario scenario = scenario();
            useCase.register(scenario.user().getId(), scenario.product().getId());
            useCase.cancel(scenario.user().getId(), scenario.product().getId());
            useCase.cancel(scenario.user().getId(), scenario.product().getId());
            entityManager.flush();
            entityManager.clear();
            assertAll(
                () -> assertThat(countRows(scenario.product().getId())).isZero(),
                () -> assertThat(countLikes(scenario.product().getId())).isZero()
            );
        }
    }

    @DisplayName("[INV-LIKE-45] 상품의 좋아요 수는 그 상품의 좋아요 관계 개수와 같다.")
    @Nested class HardCancel {
        @DisplayName("[상태 전이] 취소하면 DB의 관계 행과 좋아요 수가 모두 0이다.")
        @Test void removesRelationRowAfterCancel() {
            Scenario scenario = scenario();
            useCase.register(scenario.user().getId(), scenario.product().getId());
            useCase.cancel(scenario.user().getId(), scenario.product().getId());
            entityManager.flush();
            entityManager.clear();
            assertAll(
                () -> assertThat(countRows(scenario.product().getId())).isZero(),
                () -> assertThat(useCase.count(scenario.product().getId())).isZero()
            );
        }

        @DisplayName("[상태 전이] 취소 후 재등록하면 새 ID의 관계가 하나 생기고 반복 등록해도 좋아요 수는 1이다.")
        @Test void createsNewRelationAfterCancellation() {
            Scenario scenario = scenario();
            useCase.register(scenario.user().getId(), scenario.product().getId());
            Long originalId = entityManager.createQuery(
                "select l.id from Like l where l.productId = :productId", Long.class)
                .setParameter("productId", scenario.product().getId()).getSingleResult();
            useCase.cancel(scenario.user().getId(), scenario.product().getId());
            entityManager.flush();
            entityManager.clear();
            boolean registered = useCase.register(scenario.user().getId(), scenario.product().getId());
            boolean repeated = useCase.register(scenario.user().getId(), scenario.product().getId());
            entityManager.flush();
            entityManager.clear();
            List<Like> relations = entityManager.createQuery(
                "select l from Like l where l.productId = :productId", Like.class)
                .setParameter("productId", scenario.product().getId()).getResultList();
            assertAll(
                () -> assertThat(registered).isTrue(),
                () -> assertThat(repeated).isFalse(),
                () -> assertThat(entityManager.find(Like.class, originalId)).isNull(),
                () -> assertThat(relations).singleElement().satisfies(like -> {
                    assertThat(like.getId()).isNotEqualTo(originalId);
                    assertThat(like.getUserId()).isEqualTo(scenario.user().getId());
                    assertThat(like.getProductId()).isEqualTo(scenario.product().getId());
                }),
                () -> assertThat(countRows(scenario.product().getId())).isEqualTo(1L),
                () -> assertThat(useCase.count(scenario.product().getId())).isEqualTo(1L)
            );
        }

        @DisplayName("[오류 추측] 좋아요를 취소하면 해당 사용자·상품의 관계만 삭제된다.")
        @Test void removesOnlyRequestedRelation() {
            Scenario scenario = scenario();
            User other = persist(new User());
            Product otherProduct = persist(new Product(1L, "다른 상품", 200L));
            useCase.register(scenario.user().getId(), scenario.product().getId());
            Like othersLike = persist(new Like(other.getId(), scenario.product().getId()));
            Like myOtherLike = persist(new Like(scenario.user().getId(), otherProduct.getId()));

            useCase.cancel(scenario.user().getId(), scenario.product().getId());
            entityManager.flush();
            entityManager.clear();

            assertAll(
                () -> assertThat(countRows(scenario.product().getId())).isEqualTo(1L),
                () -> assertThat(useCase.count(scenario.product().getId())).isEqualTo(1L),
                () -> assertThat(entityManager.find(Like.class, othersLike.getId())).isNotNull(),
                () -> assertThat(entityManager.find(Like.class, myOtherLike.getId())).isNotNull(),
                () -> assertThat(useCase.findMine(scenario.user().getId(), 0, 20))
                    .extracting(Product::getId).containsExactly(otherProduct.getId())
            );
        }
    }

    @DisplayName("[INV-LIKE-23] 새로 등록하는 좋아요의 상품은 삭제되지 않은 상태다.")
    @Nested class RegisterOnActiveProductOnly {
        @DisplayName("[동등 클래스 분할] 삭제되지 않은 상품에는 좋아요를 등록해 좋아요 수가 1이 된다.")
        @Test void registers_whenProductIsActive() {
            Scenario scenario = scenario();
            useCase.register(scenario.user().getId(), scenario.product().getId());
            assertThat(useCase.count(scenario.product().getId())).isEqualTo(1L);
        }

        @DisplayName("[동등 클래스 분할] 삭제된 상품에 등록하면 없는 상품으로 거절하고, 좋아요 수는 0이다.")
        @Test void throwsProductNotFound_whenProductIsDeleted() {
            Scenario scenario = scenario();
            scenario.product().delete();
            entityManager.flush();

            CoreException result = assertThrows(CoreException.class,
                () -> useCase.register(scenario.user().getId(), scenario.product().getId()));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(countRows(scenario.product().getId())).isZero()
            );
        }
    }

    @DisplayName("[R-LIKE-03] 고객은 자신의 좋아요 상품 목록을 조회할 수 있다.")
    @Nested class FindMine {
        @DisplayName("[동등 클래스 분할] 자신의 좋아요 관계에 연결된 상품을 조회한다.")
        @Test void findsOnlyRequesterLikes() {
            Scenario mine = scenario();
            User other = persist(new User());
            Product othersProduct = persist(new Product(1L, "다른 상품", 200L));
            persist(new Like(mine.user().getId(), mine.product().getId()));
            persist(new Like(other.getId(), othersProduct.getId()));
            List<Product> result = useCase.findMine(mine.user().getId(), 0, 20);
            assertThat(result).extracting(Product::getId)
                .containsExactly(mine.product().getId());
        }
    }

    private Scenario scenario() {
        return new Scenario(persist(new User()), persist(new Product(1L, "상품", 100L)));
    }

    private long countLikes(Long productId) {
        return entityManager.createQuery(
            "select count(l) from Like l where l.productId = :productId and l.deletedAt is null",
            Long.class)
            .setParameter("productId", productId).getSingleResult();
    }

    private long countRows(Long productId) {
        return entityManager.createQuery(
            "select count(l) from Like l where l.productId = :productId", Long.class)
            .setParameter("productId", productId).getSingleResult();
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private record Scenario(User user, Product product) {
    }
}
