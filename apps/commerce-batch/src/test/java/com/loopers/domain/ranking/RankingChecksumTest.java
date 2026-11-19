package com.loopers.domain.ranking;

import com.loopers.domain.ranking.RankingChecksum.InputRow;
import com.loopers.domain.ranking.RankingChecksum.ResultRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RankingChecksumTest {

    private static final LocalDateTime AUG_17 = LocalDateTime.of(2026, 8, 17, 1, 0);
    private static final LocalDateTime AUG_18 = LocalDateTime.of(2026, 8, 18, 1, 0);
    private static final LocalDateTime AUG_19 = LocalDateTime.of(2026, 8, 19, 1, 0);

    /** 과제 §6 fixture. */
    private static final List<InputRow> FIXTURE = List.of(
        new InputRow(42, 7, 2, AUG_17),
        new InputRow(42, 8, 1, AUG_17),
        new InputRow(43, 7, 3, AUG_18),
        new InputRow(44, 9, 5, AUG_19)
    );

    private static final String EXPECTED_INPUT_TEXT =
        "42\t7\t2\t2026-08-17T01:00:00.000000Z\n"
            + "42\t8\t1\t2026-08-17T01:00:00.000000Z\n"
            + "43\t7\t3\t2026-08-18T01:00:00.000000Z\n"
            + "44\t9\t5\t2026-08-19T01:00:00.000000Z\n";

    @DisplayName("입력 직렬화는 (orderId, productId) 오름차순, TAB 구분, 고정 6자리 UTC, 마지막 행도 LF 다.")
    @Test
    void serializesInputs() {
        assertThat(RankingChecksum.serializeInputs(FIXTURE)).isEqualTo(EXPECTED_INPUT_TEXT);
    }

    @DisplayName("입력 checksum 은 직렬화 문자열의 SHA-256 소문자 hex 64자다.")
    @Test
    void inputChecksumIsSha256OfSerialization() throws Exception {
        String checksum = RankingChecksum.inputChecksum(FIXTURE);

        System.out.println("[checksum] input  = " + checksum);
        assertThat(checksum).hasSize(64).isEqualTo(sha256(EXPECTED_INPUT_TEXT));
    }

    @DisplayName("입력 순서를 섞어도 checksum 은 같다.")
    @Test
    void inputOrderDoesNotMatter() {
        List<InputRow> shuffled = List.of(FIXTURE.get(3), FIXTURE.get(1), FIXTURE.get(2), FIXTURE.get(0));

        assertThat(RankingChecksum.inputChecksum(shuffled)).isEqualTo(RankingChecksum.inputChecksum(FIXTURE));
    }

    @DisplayName("빈 목록의 checksum 은 빈 문자열의 SHA-256 이다.")
    @Test
    void emptyListChecksum() throws Exception {
        assertThat(RankingChecksum.inputChecksum(List.of())).isEqualTo(sha256(""));
        assertThat(RankingChecksum.resultChecksum(List.of())).isEqualTo(sha256(""));
    }

    @DisplayName("결과 직렬화는 ranking 오름차순 productId TAB score TAB ranking + LF 다.")
    @Test
    void serializesResults() throws Exception {
        List<ResultRow> results = List.of(new ResultRow(8, 1, 3), new ResultRow(7, 5, 1), new ResultRow(9, 5, 2));
        String expectedText = "7\t5\t1\n9\t5\t2\n8\t1\t3\n";

        String checksum = RankingChecksum.resultChecksum(results);

        System.out.println("[checksum] result = " + checksum);
        assertThat(RankingChecksum.serializeResults(results)).isEqualTo(expectedText);
        assertThat(checksum).isEqualTo(sha256(expectedText));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
