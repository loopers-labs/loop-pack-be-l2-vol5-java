package com.loopers.application.product.query;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductQuery {
    Optional<ProductView> find(long id);
    Page<ProductView> list(Long brandId, Pageable pageable, String sort);
}
