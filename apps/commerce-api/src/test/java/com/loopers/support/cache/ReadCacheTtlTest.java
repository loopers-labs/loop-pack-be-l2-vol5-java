package com.loopers.support.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

class ReadCacheTtlTest {
    @Test
    @DisplayName("같은 키도 저장마다 jitter를 다시 뽑아 TTL 270·300·330초를 생성한다")
    void samplesAgainForEveryWriteIncludingTheSameKey() {
        long[] offsets = {-30, 0, 30};
        var calls = new AtomicInteger();
        var ttl = new ReadCacheTtl(() -> offsets[calls.getAndIncrement()]);
        assertThat(ttl.getTimeToLive("same-key", "value")).isEqualTo(Duration.ofSeconds(270));
        assertThat(ttl.getTimeToLive("same-key", "value")).isEqualTo(Duration.ofSeconds(300));
        assertThat(ttl.getTimeToLive("same-key", "value")).isEqualTo(Duration.ofSeconds(330));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("기본 난수 생성기로 1,000번 생성한 TTL이 모두 270~330초 범위에 든다")
    void defaultSamplerKeepsEveryTtlWithinTheAgreedRange() {
        var ttl = new ReadCacheTtl();
        for (int i = 0; i < 1000; i++) {
            assertThat(ttl.getTimeToLive("key", "value").toSeconds()).isBetween(270L, 330L);
        }
    }
    @Test
    @DisplayName("실험 TTL 2초는 최신순 무브랜드 첫 20개 키에만 적용하고 jitter를 넣지 않는다")
    void shortTtlOnlyAppliesToTheSelectedLatestPage() {
        var ttl = new ReadCacheTtl(2, true);
        for (int i = 0; i < 20; i++) {
            assertThat(ttl.getTimeToLive(CacheKeys.products("latest", null, 0, 20), "value")).isEqualTo(Duration.ofSeconds(2));
        }
        for (String key : java.util.List.of(CacheKeys.products("price_asc", null, 0, 20), CacheKeys.products("latest", 1L, 0, 20),
            CacheKeys.products("latest", null, 1, 20), CacheKeys.products("latest", null, 0, 10), CacheKeys.product(1L),
            CacheKeys.item(CacheKeys.COUNTS, 1L), CacheKeys.item(CacheKeys.STOCKS, 1L))) {
            assertThat(ttl.getTimeToLive(key, "value").toSeconds()).isBetween(270L, 330L);
        }
    }

    @Test
    @DisplayName("짧은 TTL은 실험 프로필에서만 허용하고 음수 설정을 거절한다")
    void rejectsNonExperimentAndNegativeOverrides() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReadCacheTtl(2, false)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReadCacheTtl(-1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReadCacheTtl(0, false).getTimeToLive(CacheKeys.products("latest", null, 0, 20), "x").toSeconds()).isBetween(270L, 330L);
    }
    @Test
    void revisionLatestKeyUsesExperimentTtl() {
        assertThat(new ReadCacheTtl(5, true).getTimeToLive(
            CacheKeys.products("latest", null, 0, 20) + ":rev=old", "x").toSeconds()).isEqualTo(5);
    }
    @org.junit.jupiter.api.Test
    void writeInvalidateLatestKeyUsesExperimentTtl() {
        assertThat(new ReadCacheTtl(5, true).getTimeToLive(
            CacheKeys.products("latest", null, 0, 20), "x").toSeconds()).isEqualTo(5);
    }
}
