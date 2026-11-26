package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotChecksumTest {

    @DisplayName("입력 checksum 은 (orderId, productId) 순으로 정렬한 TAB·LF 직렬화의 SHA-256 이다. 시각은 UTC 마이크로초로 쓴다.")
    @Test
    void inputChecksum() throws Exception {
        // arrange: 일부러 순서를 섞고, 서울 시간대로 같은 순간을 넣는다
        List<SnapshotInputLine> lines = List.of(
            new SnapshotInputLine(43L, 7L, 3, ZonedDateTime.parse("2026-08-18T10:00+09:00[Asia/Seoul]")),
            new SnapshotInputLine(42L, 8L, 1, ZonedDateTime.parse("2026-08-17T01:00:00Z")),
            new SnapshotInputLine(42L, 7L, 2, ZonedDateTime.parse("2026-08-17T01:00:00.123456Z"))
        );
        String expectedText = """
            42\t7\t2\t2026-08-17T01:00:00.123456Z
            42\t8\t1\t2026-08-17T01:00:00.000000Z
            43\t7\t3\t2026-08-18T01:00:00.000000Z
            """;

        // act & assert
        assertThat(SnapshotChecksum.ofInput(lines)).isEqualTo(sha256(expectedText));
    }

    @DisplayName("결과 checksum 은 순위 순서의 productId·score·rank 를 TAB·LF 로 직렬화한 SHA-256 이다.")
    @Test
    void resultChecksum() throws Exception {
        // arrange
        List<RankedProduct> ranked = List.of(
            new RankedProduct(7L, 5, 1), new RankedProduct(9L, 5, 2), new RankedProduct(8L, 1, 3)
        );
        String expectedText = """
            7\t5\t1
            9\t5\t2
            8\t1\t3
            """;

        // act & assert
        assertThat(SnapshotChecksum.ofResult(ranked)).isEqualTo(sha256(expectedText));
    }

    private static String sha256(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
