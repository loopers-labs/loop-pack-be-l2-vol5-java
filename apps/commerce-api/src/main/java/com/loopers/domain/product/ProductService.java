package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductModel create(Long brandId, String name, long price, int initialStockQuantity) {
        ProductModel product = new ProductModel(brandId, name, price, initialStockQuantity);
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public ProductModel get(Long id) {
        return findActive(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional
    public ProductModel getForUpdate(Long id) {
        return productRepository.findForUpdate(id)
            .filter(product -> product.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Optional<ProductModel> findActive(Long id) {
        return productRepository.find(id)
            .filter(product -> product.getDeletedAt() == null);
    }

    @Transactional(readOnly = true)
    public List<ProductModel> findAllActive() {
        return productRepository.findAllActive();
    }

    @Transactional(readOnly = true)
    public List<ProductModel> findActiveByIds(Collection<Long> ids) {
        return productRepository.findActiveByIds(ids);
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getForUpdate(id);
        product.changeStock(quantity);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel update(Long id, String name, long price) {
        ProductModel product = getForUpdate(id);
        product.update(name, price);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id) {
        ProductModel product = getForUpdate(id);
        product.markDeleted();
        productRepository.save(product);
    }
}
