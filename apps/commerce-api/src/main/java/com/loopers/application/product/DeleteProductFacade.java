package com.loopers.application.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class DeleteProductFacade {
    private final ProductRepository repository;

    public void delete(long id) {
        Product product = repository.findById(id).orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
        product.delete();
        repository.save(product);
    }
}
