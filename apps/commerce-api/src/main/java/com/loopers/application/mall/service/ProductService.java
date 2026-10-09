package com.loopers.application.mall.service;

import com.loopers.application.mall.command.ProductCommand;
import com.loopers.application.mall.usecase.CreateProductUseCase;
import com.loopers.application.mall.usecase.DeleteProductUseCase;
import com.loopers.application.mall.usecase.SetProductStockUseCase;
import com.loopers.application.mall.usecase.UpdateProductUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 상품 생성·수정·삭제·재고설정 유스케이스 구현
public class ProductService implements CreateProductUseCase, UpdateProductUseCase, DeleteProductUseCase,
        SetProductStockUseCase {
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;

    // 상품 생성
    @Override
    @Transactional
    public long execute(ProductCommand.Create command) {
        Brand brand = brandRepository.findByIdForShare(command.brandId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
        brand.ensureActive();
        Product product = Product.create(command.brandId(), command.name(), command.description(), command.price(),
            command.stock());
        return productRepository.save(product).getId();
    }

    // 상품 수정
    @Override
    @Transactional
    public long execute(ProductCommand.Update command) {
        Product product = findProduct(command.productId());
        product.update(command.name(), command.description(), command.price());
        findBrand(product.getBrandId());
        return productRepository.save(product).getId();
    }

    // 상품 삭제
    @Override
    @Transactional
    public void execute(ProductCommand.Delete command) {
        Product product = findProduct(command.productId());
        product.delete();
        productRepository.save(product);
    }

    // 상품 재고 설정
    @Override
    @Transactional
    public long execute(ProductCommand.SetStock command) {
        Product product = findProduct(command.productId());
        product.setStock(command.stock());
        findBrand(product.getBrandId());
        return productRepository.save(product).getId();
    }

    // 상품 id 오름차순으로 잠가 조회한 뒤 품목 등장 순서로 모두 검증하고 통과하면 차감·저장한다.
    // 하나라도 실패하면 메모리의 재고도 바뀌지 않고 저장하지 않는다. 트랜잭션 안(파사드)에서만 호출한다
    public void decreaseStocks(Map<Long, Integer> quantityByProductId) {
        Map<Long, Product> lockedProducts = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(quantityByProductId.keySet())) {
            lockedProducts.put(productId, findProduct(productId));
        }
        for (Map.Entry<Long, Integer> entry : quantityByProductId.entrySet()) {
            lockedProducts.get(entry.getKey()).ensureCanDecreaseStock(entry.getValue());
        }
        for (Map.Entry<Long, Integer> entry : quantityByProductId.entrySet()) {
            lockedProducts.get(entry.getKey()).decreaseStock(entry.getValue());
        }
        for (Product product : lockedProducts.values()) {
            productRepository.save(product);
        }
    }

    private Product findProduct(long productId) {
        return productRepository.findByIdForUpdate(productId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PRODUCT_NOT_FOUND));
    }

    private Brand findBrand(long brandId) {
        Brand brand = brandRepository.findById(brandId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
        brand.ensureActive();
        return brand;
    }
}
