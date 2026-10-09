package com.loopers.application.shopping.event;

// 좋아요가 실제로 추가·삭제됐을 때 상품별 증감분을 알리는 이벤트
public record ProductLikeChangedEvent(long productId, long delta) {
}
