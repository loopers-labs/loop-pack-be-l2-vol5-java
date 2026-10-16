package com.loopers.application.product;

import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductListSnapshot;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductListReader {
    private final ProductQueryRepository products;
    private final ProductListRevisionRepository revisions;

    public ProductListReader(ProductQueryRepository products, ProductListRevisionRepository revisions) {
        this.products = products;
        this.revisions = revisions;
    }

    public long currentRevision() {
        return revisions.current();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductListSnapshot readSnapshot() {
        long revision = revisions.current();
        var page = products.findPage(null, 0, 20, ProductSort.LIKES_DESC, false)
            .map(product -> new ProductListSnapshot.Item(product.productId(), product.name(), product.price(),
                product.stockQuantity(), product.brandId(), product.brandName(), product.likeCount()));
        return new ProductListSnapshot(revision, page);
    }

    public PageResult<ProductInfo> readList(Long brandId, int page, int size, ProductSort sort) {
        return products.findPage(brandId, page, size, sort, false).map(ProductInfo::from);
    }
}
