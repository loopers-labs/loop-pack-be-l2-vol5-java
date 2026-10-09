package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductModel getProduct(Long id) {
        return productRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public ProductModel getProductForAdmin(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getProducts(Long brandId, ProductSortType sortType, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortType.toSort());
        if (brandId != null) {
            return productRepository.findAllActiveByBrandId(brandId, sorted);
        }
        return productRepository.findAllActive(sorted);
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getProductsForAdmin(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<ProductModel> getActiveProducts(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return productRepository.findAllActiveByIds(ids);
    }

    @Transactional
    public ProductModel createProduct(Long brandId, String name, Long price, int stock) {
        return productRepository.save(new ProductModel(brandId, name, price, stock));
    }

    @Transactional
    public ProductModel updateProduct(Long id, String name, Long price) {
        ProductModel product = getActiveProductForUpdate(id);
        product.updateNameAndPrice(name, price);
        return product;
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getActiveProductForUpdate(id);
        product.changeStock(quantity);
        return product;
    }

    @Transactional
    public void deleteProduct(Long id) {
        ProductModel product = productRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
        product.delete();
    }

    // id 오름차순으로 하나씩 잠근 뒤, 그 사이 삭제된 상품은 건너뛴다
    @Transactional
    public void deleteAllActiveByBrand(Long brandId) {
        for (Long id : productRepository.findActiveIdsByBrandId(brandId)) {
            productRepository.findByIdForUpdate(id)
                .filter(product -> product.getDeletedAt() == null)
                .ifPresent(ProductModel::delete);
        }
    }

    @Transactional
    public void decreaseStock(Long id, int quantity) {
        ProductModel product = getActiveProductForUpdate(id);
        product.decreaseStock(quantity);
    }

    private ProductModel getActiveProductForUpdate(Long id) {
        return productRepository.findByIdForUpdate(id)
            .filter(product -> product.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }
}
