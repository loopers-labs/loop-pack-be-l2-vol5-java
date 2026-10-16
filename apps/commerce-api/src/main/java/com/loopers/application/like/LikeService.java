package com.loopers.application.like;

import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductQueryException;
import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductQueryRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikeService {

    private final UserResolver userResolver;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductLikeRepository likeRepository;
    private final ProductQueryRepository productQueryRepository;
    private final ProductListRevisionRepository revisions;

    public LikeService(UserResolver userResolver, UserRepository userRepository,
                       ProductRepository productRepository, ProductLikeRepository likeRepository,
                       ProductQueryRepository productQueryRepository, ProductListRevisionRepository revisions) {
        this.userResolver = userResolver;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.likeRepository = likeRepository;
        this.productQueryRepository = productQueryRepository;
        this.revisions = revisions;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LikeInfo register(String requester, long productId) {
        User user = lockUser(requester);
        Product product = productRepository.lockById(productId).orElseThrow(this::productNotFound);
        if (product.isDeleted() || product.getBrand().isDeleted()) {
            throw productNotFound();
        }
        boolean created = !likeRepository.existsByUserIdAndProductId(user.getId(), productId);
        if (created) {
            likeRepository.save(new ProductLike(user, product));
        }
        LikeInfo result = new LikeInfo(productId, true, likeRepository.countByProductId(productId));
        if (created) {
            revisions.advance();
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LikeInfo cancel(String requester, long productId) {
        User user = lockUser(requester);
        Product product = productRepository.lockById(productId).orElseThrow(this::productNotFound);
        boolean existed = likeRepository.existsByUserIdAndProductId(user.getId(), productId);
        boolean visible = !product.isDeleted() && !product.getBrand().isDeleted();
        likeRepository.deleteByUserIdAndProductId(user.getId(), productId);
        LikeInfo result = new LikeInfo(productId, false, likeRepository.countByProductId(productId));
        if (existed && visible) {
            revisions.advance();
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResult<ProductInfo> list(String requester, String owner, int page, int size) {
        long userId = userResolver.resolve(requester).userId();
        userRepository.findById(userId).orElseThrow(UserResolutionException::userNotFound);
        if (!requester.equals(owner)) {
            throw UserResolutionException.userNotFound();
        }
        return productQueryRepository.findLikedPage(userId, page, size).map(ProductInfo::from);
    }

    private User lockUser(String requester) {
        long userId = userResolver.resolve(requester).userId();
        return userRepository.lockById(userId).orElseThrow(UserResolutionException::userNotFound);
    }

    private ProductQueryException productNotFound() {
        return new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND);
    }
}
