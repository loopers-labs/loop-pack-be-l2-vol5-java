package com.loopers.like.application;

import com.loopers.like.application.port.in.LikeCommandUseCase;
import com.loopers.like.application.port.out.LikePort;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
public class LikeCommandService implements LikeCommandUseCase {

    private final LikePort likePort;
    private final ProductPort productPort;

    /**
     * LIK-02·LIK-03: 팔 수 있는 상품에만 좋아요한다. 이미 좋아요했으면 아무것도 바뀌지 않는다.
     */
    @Transactional
    @Override
    public void like(Long userId, Long productId) {
        ProductModel product = productPort.findActiveById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
        likePort.addIfAbsent(userId, product.getId(), ZonedDateTime.now());
    }

    /**
     * LIK-02·LIK-03: 관계가 있으면 지운다. 관계가 없거나 상품이 삭제됐어도 성공이다.
     */
    @Transactional
    @Override
    public void unlike(Long userId, Long productId) {
        likePort.remove(userId, productId);
    }
}
