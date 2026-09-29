package com.loopers.domain.product;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Quantity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductService implements ProductAvailability, ProductsInBrand, StockDeduction {

    private final ProductRepository productRepository;

    public Product register(Long brandId, String name, Price price) {
        return productRepository.save(Product.register(brandId, name, price));
    }

    public Product get(Long productId) {
        return findAlive(productId);
    }

    public Product update(Long productId, String name, Price price) {
        Product product = findAliveForUpdate(productId);
        product.update(name, price);
        return productRepository.save(product);
    }

    public void delete(Long productId) {
        Product product = findAliveForUpdate(productId);
        product.delete();
        productRepository.save(product);
    }

    public Quantity getStock(Long productId) {
        return findAlive(productId).getQuantity();
    }

    public Product adjustStock(Long productId, Quantity quantity) {
        Product product = findAliveForUpdate(productId);
        product.adjustTo(quantity);
        return productRepository.save(product);
    }

    @Override
    public void deductStock(Long productId, Quantity amount) {
        Product product = findAliveForUpdate(productId);
        product.deduct(amount);
        productRepository.save(product);
    }

    @Override
    public void requireAvailable(Long productId) {
        findAlive(productId);
    }

    @Override
    public boolean hasAlive(Long brandId) {
        return productRepository.existsByBrandId(brandId);
    }

    private Product findAliveForUpdate(Long productId) {
        return productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> new DomainException(DomainError.PRODUCT_NOT_FOUND));
    }

    private Product findAlive(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new DomainException(DomainError.PRODUCT_NOT_FOUND));
    }
}
