package com.loopers.support.test;

import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.infrastructure.dao.shopping.JdbcLikeCountAggregationDao;
import com.loopers.infrastructure.persistence.mall.jpa.ProductJpaRepository;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// RANDOM_PORT 환경 E2E 테스트가 동일한 스파이 구성으로 스프링 컨텍스트를 공유하도록 하는 메타 어노테이션
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@MockitoSpyBean(types = {
    OrderRepository.class,
    ProductJpaRepository.class,
    JdbcLikeCountAggregationDao.class,
})
public @interface E2ETest {
}
