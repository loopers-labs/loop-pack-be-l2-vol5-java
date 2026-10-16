package com.loopers.like.application;

import com.loopers.brand.domain.BrandRepository;
import com.loopers.like.domain.Like;
import com.loopers.like.domain.LikeRepository;
import com.loopers.product.application.ProductUseCase.CustomerProduct;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.Stock;
import com.loopers.product.domain.StockRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.page.PageResult;
import com.loopers.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LikeUseCase {

    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final BrandRepository brandRepository;
    private final StockRepository stockRepository;

    public LikeUseCase(
        LikeRepository likeRepository,
        ProductRepository productRepository,
        UserRepository userRepository,
        BrandRepository brandRepository,
        StockRepository stockRepository
    ) {
        this.likeRepository = likeRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.brandRepository = brandRepository;
        this.stockRepository = stockRepository;
    }

    @Transactional
    public boolean register(Long userId, Long productId) {
        requireUser(userId);
        requireActiveProduct(productId);
        if (likeRepository.findByUserIdAndProductId(userId, productId).isPresent()) {
            return false;
        }
        likeRepository.save(new Like(userId, productId));
        return true;
    }

    @Transactional
    public void cancel(Long userId, Long productId) {
        requireUser(userId);
        likeRepository.findByUserIdAndProductId(userId, productId)
            .ifPresent(like -> {
                like.cancel(userId);
                likeRepository.delete(like);
            });
    }

    @Transactional(readOnly = true)
    public List<Product> findMine(Long userId, int page, int size) {
        requireUser(userId);
        return likeRepository.findAllByUserId(userId, page, size).stream()
            .map(like -> productRepository.findById(like.getProductId())
                .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND)))
            .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerProduct> findMinePage(Long requesterId, Long userId, int page, int size) {
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorCode.USER_NOT_FOUND);
        }
        List<CustomerProduct> content = findMine(userId, page, size).stream()
            .map(product -> new CustomerProduct(
                product,
                stockRepository.findByProductId(product.getId()).orElse(new Stock(product.getId(), 0)),
                brandRepository.findById(product.getBrandId())
                    .orElseThrow(() -> new CoreException(ErrorCode.BRAND_NOT_FOUND)),
                likeRepository.countByProductId(product.getId())
            ))
            .toList();
        return new PageResult<>(content, page, size, likeRepository.countAllByUserId(userId));
    }

    @Transactional(readOnly = true)
    public PageResult<com.loopers.product.application.ProductUseCase.BasicProduct> findMineBasicPage(Long requesterId, Long userId, int page, int size) {
        if (!requesterId.equals(userId)) throw new CoreException(ErrorCode.USER_NOT_FOUND);
        List<Product> products = findMine(userId, page, size);
        var brands = brandRepository.findAllByIds(products.stream().map(Product::getBrandId).distinct().toList()).stream()
            .collect(java.util.stream.Collectors.toMap(com.loopers.brand.domain.Brand::getId, java.util.function.Function.identity()));
        return new PageResult<>(products.stream().map(p -> new com.loopers.product.application.ProductUseCase.BasicProduct(p, brands.get(p.getBrandId()))).toList(),
            page, size, likeRepository.countAllByUserId(userId));
    }

    @Transactional(readOnly = true)
    public long count(Long productId) {
        return likeRepository.countByProductId(productId);
    }

    private void requireUser(Long userId) {
        if (userRepository.findById(userId).isEmpty()) {
            throw new CoreException(ErrorCode.USER_NOT_IDENTIFIED);
        }
    }

    private Product requireActiveProduct(Long productId) {
        Product product = productRepository.findForLike(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }
}
