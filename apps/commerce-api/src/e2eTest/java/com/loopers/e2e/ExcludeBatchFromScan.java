package com.loopers.e2e;

import com.loopers.CommerceBatchApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;

/**
 * E2E context에서 commerce-batch의 클래스를 commerce-api의 component scan에서 모두 뺀다 (docs/week9/design.md D7).
 *
 * <p>{@link ExcludeStreamerFromScan}과 같은 이유다. 두 앱이 같은 패키지({@code com.loopers})를 쓰므로 그대로 두면 batch의
 * {@code @SpringBootApplication}·demo job·시간대 설정까지 올라온다. 주간 랭킹 job이 쓰는 부품만 {@link WeeklyRankingJobImportConfig}로 명시한다.
 * batch는 이 source set의 classpath에만 있고 commerce-api 제품 runtime 의존성이 아니다.
 */
public final class ExcludeBatchFromScan extends TypeExcludeFilter {

    private static final String BATCH_LOCATION =
        CommerceBatchApplication.class.getProtectionDomain().getCodeSource().getLocation().toString();

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) throws IOException {
        String resource = metadataReader.getResource().getURL().toString();
        return resource.startsWith("jar:" + BATCH_LOCATION) || resource.startsWith(BATCH_LOCATION);
    }

    // context cache key에 쓰인다. 상태가 없으므로 같은 클래스면 같다
    @Override
    public boolean equals(Object other) {
        return other != null && other.getClass() == getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
