package com.loopers.application.mall.product;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
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
    private final ProductLikeCountQueryDao likeCountQueryDao;

    // 상품 생성
    @Override
    @Transactional
    public ProductResult execute(ProductCommand.Create command) {
        Brand brand = brandRepository.findById(command.brandId())
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.BRAND_NOT_FOUND));
        brand.ensureActive();
        Product product = Product.create(command.brandId(), command.name(), command.description(), command.price(),
            command.stock());
        return result(productRepository.save(product), brand);
    }

    // 상품 수정
    @Override
    @Transactional
    public ProductResult execute(ProductCommand.Update command) {
        Product product = findProduct(command.productId());
        product.update(command.name(), command.description(), command.price());
        return result(productRepository.save(product), findBrand(product.getBrandId()));
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
    public ProductResult execute(ProductCommand.SetStock command) {
        Product product = findProduct(command.productId());
        product.setStock(command.stock());
        return result(productRepository.save(product), findBrand(product.getBrandId()));
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

    private ProductResult result(Product product, Brand brand) {
        return ProductResult.from(product, brand.getName(), likeCountQueryDao.findCount(product.getId()));
    }
}
