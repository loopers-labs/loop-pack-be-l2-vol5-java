package com.loopers.infrastructure.purchaseactivity;

import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmationModel;
import com.loopers.domain.purchaseactivity.ProductPurchaseActivityModel;
import com.loopers.domain.purchaseactivity.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 두 insert 모두 saveAndFlush 로 SQL 을 즉시 실행한다. 제약 위반이 Spring Data 프록시의 예외 변환을 거쳐
 * 호출한 저장 단계에서 드러나게 하기 위해서다. 별도 TX 를 열지 않고 호출자의 TX 에 참여한다.
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityRepositoryImpl implements PurchaseActivityRepository {

    private final ProcessedOrderConfirmationJpaRepository processedJpaRepository;
    private final ProductPurchaseActivityJpaRepository activityJpaRepository;

    @Override
    public List<ProcessedOrderConfirmationModel> findProcessedByEventIdOrOrderId(String eventId, long orderId) {
        return processedJpaRepository.findByEventIdOrOrderId(eventId, orderId);
    }

    @Override
    public List<ProductPurchaseActivityModel> findActivitiesByOrderId(long orderId) {
        return activityJpaRepository.findByOrderIdOrderByProductIdAsc(orderId);
    }

    @Override
    public ProcessedOrderConfirmationModel insertProcessedAndFlush(ProcessedOrderConfirmationModel processed) {
        return processedJpaRepository.saveAndFlush(processed);
    }

    @Override
    public ProductPurchaseActivityModel saveActivity(ProductPurchaseActivityModel activity) {
        return activityJpaRepository.saveAndFlush(activity);
    }
}
