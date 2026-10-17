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

    private final ProductListingEvents listingEvents;

    public Product register(Long brandId, String name, Price price) {
        Product product = productRepository.save(Product.register(brandId, name, price));
        listingChanged(product);
        return product;
    }

    public Product get(Long productId) {
        return findAlive(productId);
    }

    public Product update(Long productId, String name, Price price) {
        Product product = findAliveForUpdate(productId);
        product.update(name, price);
        listingChanged(product);
        return productRepository.save(product);
    }

    public void delete(Long productId) {
        Product product = findAliveForUpdate(productId);
        product.delete();
        productRepository.save(product);
        listingChanged(product);
    }

    public Quantity getStock(Long productId) {
        return findAlive(productId).getQuantity();
    }

    public Product adjustStock(Long productId, Quantity quantity) {
        Product product = findAliveForUpdate(productId);
        boolean wasSoldOut = product.isSoldOut();
        product.adjustTo(quantity);
        if (product.isSoldOut() != wasSoldOut) {
            listingChanged(product);
        }
        return productRepository.save(product);
    }

    @Override
    public void deductStock(Long productId, Quantity amount) {
        Product product = findAliveForUpdate(productId);
        product.deduct(amount);
        productRepository.save(product);
        if (product.isSoldOut()) {
            listingChanged(product);
        }
    }

    @Override
    public void requireAvailable(Long productId) {
        findAlive(productId);
    }

    @Override
    public void deleteAll(Long brandId) {
        productRepository.deleteAllByBrandId(brandId);
    }

    private void listingChanged(Product product) {
        listingEvents.changed(product.getBrandId());
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
