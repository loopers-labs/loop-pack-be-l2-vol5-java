package com.loopers.infrastructure.product;

import com.loopers.domain.product.PricePage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalPricePageCacheTest {
    @Test
    void hitDoesNotExtendExpiryAndEvictionIsScoped() {
        var nanos = new AtomicLong();
        var cache = new LocalPricePageCache(300, 1000, nanos::get);
        var page = new PricePage(List.of());
        cache.put(7L, 0, 20, page);
        cache.put(8L, 0, 20, page);
        nanos.set(299_000_000_000L);
        assertThat(cache.get(7L, 0, 20)).contains(page);
        cache.evict(8L, 0, 20);
        assertThat(cache.get(8L, 0, 20)).isEmpty();
        assertThat(cache.get(7L, 1, 20)).isEmpty();
        nanos.set(300_000_000_000L);
        assertThat(cache.get(7L, 0, 20)).isEmpty();
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new LocalPricePageCache(0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalPricePageCache(300, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
