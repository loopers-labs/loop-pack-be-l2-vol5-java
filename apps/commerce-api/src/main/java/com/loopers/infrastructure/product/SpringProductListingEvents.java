package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductListingChanged;
import com.loopers.domain.product.ProductListingEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class SpringProductListingEvents implements ProductListingEvents {

    private final ApplicationEventPublisher publisher;

    @Override
    public void changed(Long brandId) {
        publisher.publishEvent(new ProductListingChanged(brandId));
    }
}
