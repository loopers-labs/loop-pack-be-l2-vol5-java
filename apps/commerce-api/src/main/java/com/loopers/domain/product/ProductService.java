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
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductRevisionService productRevisionService;
    private final ProductListRevisionService productListRevisionService;

    @Transactional(readOnly = true)
    public ProductModel getProduct(Long id) {
        return productRepository.findActiveById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Optional<Long> findActiveBrandId(Long id) {
        return productRepository.findActiveById(id).map(ProductModel::getBrandId);
    }

    @Transactional(readOnly = true)
    public ProductModel getProductForAdmin(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getProducts(Long brandId, ProductSortType sortType, Pageable pageable) {
        if (sortType == ProductSortType.LIKES_DESC) {
            Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
            if (brandId != null) {
                return productRepository.findAllActiveByBrandIdOrderByLikeCountDesc(brandId, unsorted);
            }
            return productRepository.findAllActiveOrderByLikeCountDesc(unsorted);
        }
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

    // 새 상품은 상품 캐시에 없으므로 목록 revision만 올린다
    @Transactional
    public ProductModel createProduct(Long brandId, String name, Long price, int stock) {
        ProductModel product = productRepository.save(new ProductModel(brandId, name, price, stock));
        productListRevisionService.increaseForMembership(brandId);
        return product;
    }

    // 이름만 바뀌면 목록 순서는 그대로라 상품 revision만 올린다
    @Transactional
    public ProductModel updateProduct(Long id, String name, Long price) {
        ProductModel product = getActiveProductForUpdate(id);
        boolean priceChanged = !product.getPrice().equals(price);
        product.updateNameAndPrice(name, price);
        productRevisionService.increase(id);
        if (priceChanged) {
            productListRevisionService.increaseForPrice(product.getBrandId());
        }
        return product;
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getActiveProductForUpdate(id);
        product.changeStock(quantity);
        productRevisionService.increase(id);
        return product;
    }

    @Transactional
    public void deleteProduct(Long id) {
        ProductModel product = productRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
        product.delete();
        productRevisionService.increase(id);
        productListRevisionService.increaseForMembership(product.getBrandId());
    }

    // id 오름차순으로 하나씩 잠근 뒤, 그 사이 삭제된 상품은 건너뛴다.
    // 상품 revision은 올리지 않는다 — 삭제된 상품은 목록 revision이 바뀐 목록에 나오지 않아 상품 캐시를 다시 읽지 않는다
    @Transactional
    public void deleteAllActiveByBrand(Long brandId) {
        for (Long id : productRepository.findActiveIdsByBrandId(brandId)) {
            productRepository.findByIdForUpdate(id)
                .filter(product -> product.getDeletedAt() == null)
                .ifPresent(ProductModel::delete);
        }
    }

    // 상품 revision은 올리지 않는다 — 주문 확정이 품목을 모두 처리한 뒤 상품 id 오름차순으로 한꺼번에 올린다
    @Transactional
    public ProductModel decreaseStock(Long id, int quantity) {
        ProductModel product = getActiveProductForUpdate(id);
        product.decreaseStock(quantity);
        return product;
    }

    // 확보했던 재고를 되돌린다. 삭제된 상품도 잠금 조회로 읽어 재고만 늘리고 삭제 시각은 건드리지 않아, 반환이 삭제 상품을 다시 노출하지 않는다.
    // 상품 revision은 올리지 않는다 — 결제 결과 적용이 품목을 모두 처리한 뒤 상품 id 오름차순으로 한꺼번에 올린다
    @Transactional
    public void increaseStock(Long id, int quantity) {
        ProductModel product = productRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
        product.increaseStock(quantity);
    }

    private ProductModel getActiveProductForUpdate(Long id) {
        return productRepository.findByIdForUpdate(id)
            .filter(product -> product.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }
}
