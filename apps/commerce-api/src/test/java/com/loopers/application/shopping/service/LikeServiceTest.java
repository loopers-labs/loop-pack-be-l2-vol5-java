package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.loopers.application.shopping.command.LikeCommand;
import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.repository.LikeRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class LikeServiceTest {
    private final LikeRepository likeRepository = mock(LikeRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final LikeService service = new LikeService(likeRepository, productRepository, eventPublisher);

    @DisplayName("좋아요 등록")
    @Nested
    class Register {
        @DisplayName("상품이 없으면 PRODUCT_NOT_FOUND이고 저장하지 않는다")
        @Test
        void throwsProductNotFound_whenProductMissing() {
            given(productRepository.findById(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.execute(new LikeCommand.Register(1L, 10L)))
                .isInstanceOfSatisfying(ApplicationException.class,
                    e -> assertThat(e.getErrorCode())
                        .isEqualTo(ApplicationErrorCode.PRODUCT_NOT_FOUND));
            verify(likeRepository, never()).save(any(Like.class));
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("삭제된 상품이면 DELETED_PRODUCT이고 저장하지 않는다")
        @Test
        void throwsDeletedProduct_whenProductDeleted() {
            given(productRepository.findById(10L)).willReturn(Optional.of(product(10L, true)));

            assertThatThrownBy(() -> service.execute(new LikeCommand.Register(1L, 10L)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);
            verify(likeRepository, never()).save(any(Like.class));
            verifyNoInteractions(eventPublisher);
        }

        @DisplayName("활성 상품이면 해당 사용자·상품으로 저장한다")
        @Test
        void savesLike_whenProductActive() {
            given(productRepository.findById(10L)).willReturn(Optional.of(product(10L, false)));
            given(likeRepository.save(any(Like.class))).willReturn(true);

            service.execute(new LikeCommand.Register(1L, 10L));

            ArgumentCaptor<Like> captor = ArgumentCaptor.forClass(Like.class);
            verify(likeRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(1L);
            assertThat(captor.getValue().getProductId()).isEqualTo(10L);
        }

        @DisplayName("새로 저장되면 상품 id와 +1 증감분 이벤트를 발행한다")
        @Test
        void publishesPlusOne_whenSaved() {
            given(productRepository.findById(10L)).willReturn(Optional.of(product(10L, false)));
            given(likeRepository.save(any(Like.class))).willReturn(true);

            service.execute(new LikeCommand.Register(1L, 10L));

            verify(eventPublisher).publishEvent(new ProductLikeChangedEvent(10L, 1L));
        }

        @DisplayName("이미 있던 좋아요면 이벤트를 발행하지 않는다")
        @Test
        void publishesNothing_whenAlreadyLiked() {
            given(productRepository.findById(10L)).willReturn(Optional.of(product(10L, false)));
            given(likeRepository.save(any(Like.class))).willReturn(false);

            service.execute(new LikeCommand.Register(1L, 10L));

            verifyNoInteractions(eventPublisher);
        }
    }

    @DisplayName("좋아요 취소")
    @Nested
    class Cancel {
        @DisplayName("상품을 조회하지 않고 삭제를 호출한다")
        @Test
        void deletesWithoutLookingUpProduct() {
            service.execute(new LikeCommand.Cancel(1L, 10L));

            verify(likeRepository).delete(1L, 10L);
            verifyNoInteractions(productRepository);
        }

        @DisplayName("실제로 지웠으면 상품 id와 -1 증감분 이벤트를 발행한다")
        @Test
        void publishesMinusOne_whenDeleted() {
            given(likeRepository.delete(1L, 10L)).willReturn(true);

            service.execute(new LikeCommand.Cancel(1L, 10L));

            verify(eventPublisher).publishEvent(new ProductLikeChangedEvent(10L, -1L));
        }

        @DisplayName("지울 좋아요가 없으면 이벤트를 발행하지 않는다")
        @Test
        void publishesNothing_whenNothingDeleted() {
            given(likeRepository.delete(1L, 10L)).willReturn(false);

            service.execute(new LikeCommand.Cancel(1L, 10L));

            verifyNoInteractions(eventPublisher);
        }
    }

    private Product product(long id, boolean deleted) {
        return Product.restore(id, 1L, "상품", null, 1_000L, 100, deleted, Instant.now());
    }
}
