package com.loopers.application.like;

import com.loopers.application.product.query.ProductView;
import com.loopers.application.user.IdentifyUser;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GetLikeFacade {
    private final IdentifyUser identifyUser;
    private final LikedProductQuery query;

    public List<ProductView> get(Long userId, long ownerId) {
        long id = identifyUser.require(userId);
        if (id != ownerId) {
            throw new CoreException(ErrorType.ACCESS_DENIED);
        }
        return query.findByUser(id);
    }
}
