package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class StockReservationService {

    private final StockReservationRepository stockReservationRepository;

    // 재고를 이미 줄인 트랜잭션 안에서 부른다. 예약 행은 그 재고 차감과 같이 commit된다
    @Transactional
    public StockReservationModel reserve(Long orderId) {
        return stockReservationRepository.save(new StockReservationModel(orderId));
    }

    // 주문과 결제 시도를 이미 잠근 트랜잭션 안에서 부른다 (design.md 9-5절 잠금 순서)
    @Transactional
    public StockReservationModel getByOrderIdForUpdate(Long orderId) {
        return stockReservationRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 재고 예약을 찾을 수 없습니다."));
    }
}
