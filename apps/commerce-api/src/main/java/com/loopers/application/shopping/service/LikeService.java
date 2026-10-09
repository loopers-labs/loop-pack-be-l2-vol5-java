package com.loopers.application.shopping.service;

import com.loopers.application.shopping.command.LikeCommand;
import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import com.loopers.application.shopping.usecase.CancelLikeUseCase;
import com.loopers.application.shopping.usecase.RegisterLikeUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.repository.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 좋아요 등록·취소 유스케이스 구현
public class LikeService implements RegisterLikeUseCase, CancelLikeUseCase {
    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 활성 상품인지 확인한 뒤 좋아요 등록. 실제로 새로 저장됐을 때만 변경 이벤트 발행
    @Override
    @Transactional
    public void execute(LikeCommand.Register command) {
        Product product = productRepository.findById(command.productId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PRODUCT_NOT_FOUND));
        product.ensureActive();
        if (likeRepository.save(Like.create(command.userId(), command.productId()))) {
            eventPublisher.publishEvent(new ProductLikeChangedEvent(command.productId(), 1L));
        }
    }

    // 상품 확인 없이 좋아요 취소. 실제로 지웠을 때만 변경 이벤트 발행
    @Override
    @Transactional
    public void execute(LikeCommand.Cancel command) {
        if (likeRepository.delete(command.userId(), command.productId())) {
            eventPublisher.publishEvent(new ProductLikeChangedEvent(command.productId(), -1L));
        }
    }
}
