package com.loopers.e2e;

import com.loopers.CommerceStreamerApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;

/**
 * E2E context에서 commerce-streamer의 클래스를 commerce-api의 component scan에서 모두 뺀다 (docs/week7/design.md 5절 결정 B).
 *
 * <p>두 앱은 같은 패키지({@code com.loopers})를 쓰므로, 그대로 두면 streamer의 {@code @SpringBootApplication}·demo consumer까지 함께 올라온다.
 * 이 filter는 streamer jar에서 읽힌 클래스인지를 위치로 판단해 scan에서 빼고, 실제로 쓰는 consumer 부품만 테스트가 {@code @Import}로 명시한다.
 * streamer에 새 component가 생겨도 자동으로 빠진다. 엔티티·JPA repository는 공통 {@code JpaConfig}의 패키지 scan이 따로 맡는다.
 */
public final class ExcludeStreamerFromScan extends TypeExcludeFilter {

    private static final String STREAMER_LOCATION =
        CommerceStreamerApplication.class.getProtectionDomain().getCodeSource().getLocation().toString();

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) throws IOException {
        String resource = metadataReader.getResource().getURL().toString();
        // jar면 "jar:file:/.../commerce-streamer-*-plain.jar!/com/...", 클래스 디렉터리면 "file:/.../classes/java/main/com/..."
        return resource.startsWith("jar:" + STREAMER_LOCATION) || resource.startsWith(STREAMER_LOCATION);
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
