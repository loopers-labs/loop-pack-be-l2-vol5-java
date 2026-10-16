package com.loopers.product.infrastructure;

import com.loopers.product.domain.Product;
import com.loopers.product.domain.RankedProduct;
import com.loopers.product.domain.ProductRepository;
import com.loopers.product.domain.ProductSort;
import jakarta.persistence.EntityManager;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DatabaseConstraintErrors;
import com.loopers.support.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ProductRepositoryAdapter implements ProductRepository {

    private final ProductJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public ProductRepositoryAdapter(
        ProductJpaRepository jpaRepository,
        EntityManager entityManager
    ) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public List<Product> findActiveByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return entityManager.createQuery("select p from Product p, Brand b where p.brandId = b.id "
            + "and p.deletedAt is null and b.deletedAt is null and p.id in :ids order by p.id", Product.class)
            .setParameter("ids", ids).getResultList();
    }

    @Override
    public Product save(Product product) {
        try {
            Product saved;
            if (product.getId() == 0L) {
                entityManager.persist(product);
                saved = product;
            } else {
                saved = jpaRepository.save(product);
            }
            entityManager.flush();
            return saved;
        } catch (RuntimeException failure) {
            if (DatabaseConstraintErrors.isDuplicateKey(failure, "uk_product_active_name")) {
                throw new CoreException(ErrorCode.DUPLICATE_PRODUCT_NAME);
            }
            throw failure;
        }
    }

    @Override
    public Optional<Product> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Product> findForOrder(Long id) {
        return jpaRepository.findForOrder(id);
    }

    @Override
    public Optional<Product> findForLike(Long id) {
        return jpaRepository.findForLike(id);
    }

    @Override
    public Optional<Product> findForStock(Long id) {
        return jpaRepository.findForStock(id);
    }

    @Override
    public Optional<Product> findForWrite(Long id) {
        return jpaRepository.findForWrite(id);
    }

    @Override
    public List<Product> findAllForBrandDelete(Long brandId) {
        return jpaRepository.findAllForBrandDelete(brandId);
    }

    @Override
    public List<Product> findAllByBrandId(Long brandId) {
        return jpaRepository.findAllByBrandId(brandId);
    }

    @Override
    public List<Product> findAllByBrandIdAndName(Long brandId, String name) {
        return jpaRepository.findAllByBrandIdAndName(brandId, name);
    }

    @Override
    public List<Product> findAll(Long brandId, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        PageRequest pageRequest = PageRequest.of(page, size, sort);
        if (brandId == null) {
            return jpaRepository.findAll(pageRequest).getContent();
        }
        return jpaRepository.findAllByBrandId(brandId, pageRequest);
    }

    @Override
    public List<Product> findCustomerProducts(
        Long brandId,
        ProductSort sort,
        int page,
        int size
    ) {
        return findCustomerProductWindow(brandId, sort, (long) page * size, size);
    }

    @Override
    public List<Product> findCustomerProductWindow(Long brandId, ProductSort sort, long offset, int limit) {
        Pageable pageable = new ProductWindow(offset, limit);
        return switch (sort) {
            case LATEST -> jpaRepository.findCustomerProductsByLatest(brandId, pageable);
            case PRICE_ASC -> jpaRepository.findCustomerProductsByPrice(brandId, pageable);
            case LIKES_DESC -> findCustomerProductsByLikes(brandId, offset, limit).stream()
                .map(RankedProduct::product).toList();
        };
    }

    @Override
    public List<RankedProduct> findCustomerProductsByLikes(Long brandId, long offset, int limit) {
        Pageable pageable = new ProductWindow(offset, limit);
        if (brandId == null) {
            return findUnfilteredProductsByLikes(pageable);
        }
        List<ProductJpaRepository.RankedCandidate> candidates = jpaRepository.findLikedCustomerProducts(
            brandId, pageable.getOffset(), pageable.getPageSize()
        );
        List<RankedProduct> liked = loadRankedProducts(candidates);
        int remaining = pageable.getPageSize() - liked.size();
        if (remaining == 0) {
            return liked;
        }

        // A partial positive page reaches the zero-like segment at its first row.
        // An empty positive page needs the filtered positive count to translate the offset.
        long zeroOffset = 0;
        if (liked.isEmpty() && pageable.getOffset() > 0) {
            zeroOffset = Math.max(0, pageable.getOffset() - jpaRepository.countLikedCustomerProducts(brandId));
        }
        List<RankedProduct> result = new ArrayList<>(liked);
        result.addAll(jpaRepository.findUnlikedCustomerProducts(brandId, zeroOffset, remaining).stream()
            .map(product -> new RankedProduct(product, 0)).toList());
        return result;
    }

    private List<RankedProduct> findUnfilteredProductsByLikes(Pageable pageable) {
        int pageSize = pageable.getPageSize();
        long remainingSkip = pageable.getOffset();
        long candidateOffset = 0;
        int batchSize = (int) Math.min(1024L, remainingSkip + pageSize);
        List<ProductJpaRepository.RankedCandidate> selected = new ArrayList<>();

        while (selected.size() < pageSize) {
            List<ProductJpaRepository.RankedCandidate> candidates =
                jpaRepository.findRankedCandidates(candidateOffset, batchSize);
            for (ProductJpaRepository.RankedCandidate candidate : candidates) {
                if (candidate.getEligibleProductId() == null) {
                    continue;
                }
                if (remainingSkip > 0) {
                    remainingSkip--;
                } else {
                    selected.add(candidate);
                    if (selected.size() == pageSize) {
                        break;
                    }
                }
            }
            candidateOffset += candidates.size();
            if (selected.size() == pageSize || candidates.size() < batchSize) {
                break;
            }
            batchSize = Math.min(1024, batchSize * 2);
        }

        List<RankedProduct> result = new ArrayList<>(loadRankedProducts(selected));
        if (result.size() < pageSize) {
            result.addAll(jpaRepository.findUnlikedCustomerProducts(
                null, remainingSkip, pageSize - result.size()
            ).stream().map(product -> new RankedProduct(product, 0)).toList());
        }
        return result;
    }

    private List<RankedProduct> loadRankedProducts(List<ProductJpaRepository.RankedCandidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        List<Long> ids = candidates.stream().map(ProductJpaRepository.RankedCandidate::getEligibleProductId).toList();
        Map<Long, Product> products = jpaRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
        return candidates.stream().map(candidate -> new RankedProduct(
            products.get(candidate.getEligibleProductId()), candidate.getLikeCount()
        )).toList();
    }

    @Override
    public long countAll(Long brandId) {
        if (brandId == null) {
            return jpaRepository.count();
        }
        return jpaRepository.countByBrandId(brandId);
    }

    /** Fetch limit and offset are independent so lookahead never shifts the next page. */
    private static class ProductWindow extends PageRequest {
        private final long offset;

        ProductWindow(long offset, int limit) {
            super(0, limit, Sort.unsorted());
            this.offset = offset;
        }

        @Override
        public long getOffset() {
            return offset;
        }
    }
}
