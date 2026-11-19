package com.loopers.batch.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingChecksumTest {

    @DisplayName("검증 기록의 fixture 결과는 기대 checksum과 같다.")
    @Test
    void matchesDocumentedValue() {
        String checksum = new RankingChecksum()
            .add(7, 5, 1)
            .add(9, 5, 2)
            .add(8, 1, 3)
            .hex();

        assertThat(checksum).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    @DisplayName("0건이면 빈 문자열의 SHA-256이다.")
    @Test
    void emptyIsSha256OfEmptyString() {
        assertThat(new RankingChecksum().hex()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @DisplayName("hex()를 불러도 상태가 이어져, 다시 불러도 같은 값이고 뒤에 더하면 이어서 계산한다.")
    @Test
    void hexKeepsState() {
        RankingChecksum checksum = new RankingChecksum().add(7, 5, 1).add(9, 5, 2);

        String partial = checksum.hex();

        assertThat(checksum.hex()).isEqualTo(partial);
        assertThat(checksum.add(8, 1, 3).hex()).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    @DisplayName("계산 순위 오름차순이 아니면 거절한다.")
    @Test
    void rejectsUnorderedRank() {
        RankingChecksum checksum = new RankingChecksum().add(7, 5, 2);

        assertThatThrownBy(() -> checksum.add(9, 5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checksum.add(9, 5, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
