package com.loopers.job.ranking.domain;

import com.loopers.batch.job.ranking.domain.RankingChecksum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankingChecksumTest {
    @Test
    @DisplayName("같은 입력의 체크섬을 두 번 읽어도 결과는 같다")
    void repeatedHexReturnsSameChecksum() {
        var checksum = RankingChecksum.start();
        checksum.resultLine(7, 5, 1);
        String first = checksum.hex();

        String second = checksum.hex();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("체크섬을 읽은 뒤 행을 추가해도 이전 입력을 포함한다")
    void addingLineAfterHexKeepsPreviousInput() {
        var checksum = RankingChecksum.start();
        checksum.resultLine(7, 5, 1);
        checksum.hex();
        checksum.resultLine(9, 5, 2);

        String result = checksum.hex();
        String expected = RankingChecksum.start().resultLine(7, 5, 1).resultLine(9, 5, 2).hex();

        assertThat(result).isEqualTo(expected);
    }
}
