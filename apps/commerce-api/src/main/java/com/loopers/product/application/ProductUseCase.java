package com.loopers.product.application;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandRepository;
import com.loopers.like.domain.LikeRepository;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.RankedProduct;
import com.loopers.product.domain.ProductNameValidator;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductSort;
import com.loopers.product.domain.Stock;
import com.loopers.product.domain.StockRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.page.PageResult;
import com.loopers.support.page.SliceResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductUseCase {

    private final ProductRepository productRepository;
    private final StockRepository stockRepository;
    private final BrandRepository brandRepository;
    private final LikeRepository likeRepository;
    private final ProductNameValidator nameValidator;

    public ProductUseCase(
        ProductRepository productRepository,
        StockRepository stockRepository,
        BrandRepository brandRepository,
        LikeRepository likeRepository
    ) {
        this.productRepository = productRepository;
        this.stockRepository = stockRepository;
        this.brandRepository = brandRepository;
        this.likeRepository = likeRepository;
        this.nameValidator = new ProductNameValidator(productRepository);
    }

    @Transactional
    public Product create(Long brandId, String name, long price) {
        requireActiveBrand(brandId);
        Product product = new Product(brandId, name, price);
        nameValidator.validateNotDuplicated(brandId, product.getName());
        Product saved = productRepository.save(product);
        stockRepository.save(new Stock(saved.getId(), 0));
        return saved;
    }

    @Transactional
    public Product changeStock(Long productId, int quantity) {
        Product product = productRepository.findForStock(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.isDeleted()) throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        Stock stock = stockRepository.findForStockUpdateByProductId(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
        stock.changeQuantity(quantity);
        stockRepository.save(stock);
        return product;
    }

    @Transactional(readOnly = true)
    public Product find(Long productId) {
        return findRequired(productId);
    }

    @Transactional(readOnly = true)
    public List<Product> findAll(Long brandId, int page, int size) {
        return productRepository.findAll(brandId, page, size);
    }

    @Transactional
    public Product update(Long productId, String name, long price, Long brandId) {
        Product product = productRepository.findForWrite(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        Long requestedBrandId = brandId == null ? product.getBrandId() : brandId;
        if (!product.getBrandId().equals(requestedBrandId)) {
            throw new CoreException(ErrorCode.BRAND_CHANGE_NOT_ALLOWED);
        }
        Product candidate = new Product(requestedBrandId, name, price);
        nameValidator.validateNotDuplicated(
            product.getBrandId(),
            candidate.getName(),
            productId
        );
        product.update(candidate.getName(), candidate.getPrice(), requestedBrandId);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long productId) {
        Product product = productRepository.findForWrite(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
        product.delete();
        productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public List<ProductView> findCustomerProducts(
        Long brandId,
        ProductSort sort,
        int page,
        int size
    ) {
        if (brandId != null && !isActiveBrand(brandId)) {
            return List.of();
        }
        ProductSort requestedSort = sort == null ? ProductSort.LATEST : sort;
        if (requestedSort == ProductSort.LIKES_DESC) {
            List<RankedProduct> ranked = productRepository.findCustomerProductsByLikes(brandId, (long) page * size, size);
            return toProductViews(ranked.stream().map(RankedProduct::product).toList(), rankingCounts(ranked));
        }
        return toProductViews(productRepository.findCustomerProducts(brandId, requestedSort, page, size));
    }

    @Transactional(readOnly = true)
    public SliceResult<CustomerProduct> findCustomerProductPage(
        Long brandId,
        ProductSort sort,
        int page,
        int size
    ) {
        if (brandId != null && !isActiveBrand(brandId)) {
            return new SliceResult<>(List.of(), page, size, true);
        }
        ProductSort requestedSort = sort == null ? ProductSort.LATEST : sort;
        List<RankedProduct> ranked = requestedSort == ProductSort.LIKES_DESC
            ? productRepository.findCustomerProductsByLikes(brandId, (long) page * size, size + 1) : null;
        List<Product> products = ranked == null
            ? productRepository.findCustomerProductWindow(brandId, requestedSort, (long) page * size, size + 1)
            : ranked.stream().map(RankedProduct::product).toList();
        List<Product> selected = products.stream().limit(size).toList();
        Map<Long, Brand> brands = brandRepository.findAllByIds(
            selected.stream().map(Product::getBrandId).distinct().toList()
        ).stream().collect(Collectors.toMap(Brand::getId, Function.identity()));
        List<ProductView> views = ranked == null ? toProductViews(selected)
            : toProductViews(selected, rankingCounts(ranked));
        List<CustomerProduct> content = views.stream()
            .map(view -> {
                Brand brand = brands.get(view.product().getBrandId());
                if (brand == null) throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
                return new CustomerProduct(view.product(), view.stock(), brand, view.likeCount());
            })
            .toList();
        return new SliceResult<>(content, page, size, products.size() <= size);
    }

    @Transactional(readOnly = true)
    public SliceResult<BasicProduct> findBasicPage(Long brandId, ProductSort sort, int page, int size) {
        if (brandId != null && !isActiveBrand(brandId)) return new SliceResult<>(List.of(), page, size, true);
        ProductSort requested = sort == null ? ProductSort.LATEST : sort;
        List<Product> products = requested == ProductSort.LIKES_DESC
            ? productRepository.findCustomerProductsByLikes(brandId, (long) page * size, size + 1).stream().map(RankedProduct::product).toList()
            : productRepository.findCustomerProductWindow(brandId, requested, (long) page * size, size + 1);
        return new SliceResult<>(basicProducts(products.stream().limit(size).toList()), page, size, products.size() <= size);
    }

    @Transactional(readOnly = true)
    public BasicProduct findBasicProduct(Long id) {
        List<Product> products = productRepository.findActiveByIds(List.of(id));
        if (products.isEmpty()) throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        return basicProducts(products).getFirst();
    }

    public List<BasicProduct> basicProducts(List<Product> products) {
        Map<Long, Brand> brands = brandRepository.findAllByIds(products.stream().map(Product::getBrandId).distinct().toList())
            .stream().collect(Collectors.toMap(Brand::getId, Function.identity()));
        return products.stream().map(p -> new BasicProduct(p, brands.get(p.getBrandId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<LikeCount> findLikeCounts(List<Long> ids) {
        List<Product> products = productRepository.findActiveByIds(ids);
        Map<Long, Long> counts = likeRepository.countByProductIds(products.stream().map(Product::getId).toList());
        return products.stream().map(p -> new LikeCount(p.getId(), counts.getOrDefault(p.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public List<StockStatus> findStockStatuses(List<Long> ids) {
        List<Product> products = productRepository.findActiveByIds(ids);
        Map<Long, Stock> stocks = stockRepository.findAllByProductIds(products.stream().map(Product::getId).toList()).stream()
            .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
        return products.stream().map(p -> new StockStatus(p.getId(), !stocks.containsKey(p.getId()) || stocks.get(p.getId()).quantity() == 0)).toList();
    }

    public record BasicProduct(Product product, Brand brand) { }
    public record LikeCount(Long productId, long likeCount) { }
    public record StockStatus(Long productId, boolean soldOut) { }

    private Map<Long, Long> rankingCounts(List<RankedProduct> ranked) {
        return ranked.stream().collect(Collectors.toMap(row -> row.product().getId(), RankedProduct::likeCount));
    }

    private List<ProductView> toProductViews(List<Product> products) {
        if (products.isEmpty()) return List.of();
        return toProductViews(products, likeRepository.countByProductIds(products.stream().map(Product::getId).toList()));
    }

    private List<ProductView> toProductViews(List<Product> products, Map<Long, Long> likes) {
        if (products.isEmpty()) return List.of();
        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, Stock> stocks = stockRepository.findAllByProductIds(ids).stream()
            .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
        return products.stream().map(product -> new ProductView(
            product, stocks.getOrDefault(product.getId(), new Stock(product.getId(), 0)),
            likes.getOrDefault(product.getId(), 0L)
        )).toList();
    }

    @Transactional(readOnly = true)
    public CustomerProduct findCustomerProduct(Long productId) {
        Product product = findRequired(productId);
        if (product.isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return new CustomerProduct(
                product, stockRepository.findByProductId(product.getId()).orElse(new Stock(product.getId(), 0)),
            findBrand(product.getBrandId()),
            likeRepository.countByProductId(productId)
        );
    }

    @Transactional(readOnly = true)
    public PageResult<AdminProduct> findAdminProductPage(Long brandId, int page, int size) {
        List<AdminProduct> content = productRepository.findAll(brandId, page, size).stream()
            .map(product -> new AdminProduct(product,
                stockRepository.findByProductId(product.getId()).orElse(new Stock(product.getId(), 0)),
                findBrand(product.getBrandId())))
            .toList();
        return new PageResult<>(content, page, size, productRepository.countAll(brandId));
    }

    @Transactional(readOnly = true)
    public AdminProduct findAdminProduct(Long productId) {
        Product product = findRequired(productId);
        return new AdminProduct(product,
            stockRepository.findByProductId(product.getId()).orElse(new Stock(product.getId(), 0)),
            findBrand(product.getBrandId()));
    }

    private Product findRequired(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private Brand requireActiveBrand(Long brandId) {
        Brand brand = brandRepository.findForProductCreate(brandId)
            .orElseThrow(() -> new CoreException(ErrorCode.BRAND_NOT_FOUND));
        if (brand.isDeleted()) {
            throw new CoreException(ErrorCode.BRAND_NOT_FOUND);
        }
        return brand;
    }

    private boolean isActiveBrand(Long brandId) {
        return brandRepository.findById(brandId)
            .filter(brand -> !brand.isDeleted())
            .isPresent();
    }

    private Brand findBrand(Long brandId) {
        return brandRepository.findById(brandId)
            .orElseThrow(() -> new CoreException(ErrorCode.BRAND_NOT_FOUND));
    }

    public record ProductView(Product product, Stock stock, long likeCount) {
    }

    public record CustomerProduct(Product product, Stock stock, Brand brand, long likeCount) {
    }

    public record AdminProduct(Product product, Stock stock, Brand brand) {
    }
}
