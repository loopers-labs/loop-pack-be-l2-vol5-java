package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductModel getActiveProduct(Long id) {
        return productRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public ProductModel getProduct(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getActiveProducts(Long brandId, ProductSortType sortType, int page, int size) {
        return productRepository.findActiveProducts(brandId, sortType, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public List<ProductModel> getActiveProductsByIds(List<Long> ids) {
        return productRepository.findAllActiveByIds(ids);
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getProductsForAdmin(int page, int size) {
        return productRepository.findAll(PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveProductsOfBrand(Long brandId) {
        return productRepository.existsActiveByBrandId(brandId);
    }

    @Transactional
    public ProductModel create(Long brandId, String name, Long price, int stock) {
        return productRepository.save(new ProductModel(brandId, name, price, stock));
    }

    @Transactional
    public ProductModel update(Long id, String name, Long price) {
        ProductModel product = getActiveProduct(id);
        product.update(name, price);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel deductStock(Long id, int quantity) {
        ProductModel product = productRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.BAD_REQUEST,
                "[id = " + id + "] 삭제되었거나 존재하지 않는 상품은 주문할 수 없습니다."));
        product.deductStock(quantity);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getActiveProduct(id);
        product.changeStock(quantity);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id) {
        ProductModel product = getProduct(id);
        product.delete();
        productRepository.save(product);
    }
}
