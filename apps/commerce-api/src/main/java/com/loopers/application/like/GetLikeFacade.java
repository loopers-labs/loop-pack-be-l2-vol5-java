package com.loopers.application.like;

import com.loopers.application.user.IdentifyUser;
import lombok.RequiredArgsConstructor;
import com.loopers.application.product.query.ProductView;
import java.util.List;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

@RequiredArgsConstructor
public class GetLikeFacade {
    private final IdentifyUser identifyUser;
    private final LikedProductQuery query;
    public List<ProductView> get(Long userId, long ownerId) {
        long id = identifyUser.require(userId);
        if (id != ownerId) { throw new CoreException(ErrorType.ACCESS_DENIED); }
        return query.findByUser(id);
    }
}
