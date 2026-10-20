package com.loopers.product.application;

import com.loopers.product.application.port.out.ProductListCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 목록 캐시를 무효화한다 (W4). commit 뒤에 부르는 것은 이벤트 리스너가 맡는다 (ProductListCacheEventListener).
 * <p>
 * 무효화가 실패해도 이미 commit된 변경을 실패로 알리지 않는다. 대신 그 목록은 TTL이 지날 때까지 옛 값일 수 있다.
 * {@code @Transactional} 하나가 MySQL과 Redis를 원자적으로 묶지는 못한다 — 그래서 실패 창을 TTL로 제한한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ProductListCacheInvalidator {

    private final ProductListCachePort productListCachePort;

    public void invalidate() {
        try {
            productListCachePort.invalidateAll();
        } catch (RuntimeException e) {
            // afterCommit에서 던지면 commit된 요청이 500으로 응답한다. 여기서 멈추고 기록만 한다.
            log.warn("상품 목록 캐시 무효화 실패 — TTL이 지날 때까지 옛 목록이 보일 수 있습니다.", e);
        }
    }
}
