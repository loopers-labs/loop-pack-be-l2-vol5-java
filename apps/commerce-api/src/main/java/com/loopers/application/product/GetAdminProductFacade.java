package com.loopers.application.product;

import com.loopers.application.PagePolicy;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAdminProductFacade {
    private final ProductRepository repository;

    public ProductInfo admin(long id) {
        return ProductInfo.from(
                repository
                        .findById(id)
                        .orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND)));
    }

    public Page<ProductInfo> list(int page, int size) {
        PagePolicy.validate(page, size);
        return repository
                .findAll(
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .map(ProductInfo::from);
    }
}
