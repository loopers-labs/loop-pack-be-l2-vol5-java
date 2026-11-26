package com.loopers.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 경로 내용은 LogFileLocationReporterTest가 본다. 여기서는 기동이 끝날 때 보고가 한 번 불리는 연결만 본다.
// test 프로필은 파일 appender가 없어 보고 본문은 아무 줄도 남기지 않는다.
// spy 때문에 다른 클래스와 공유되지 않는 컨텍스트라, 끝나면 닫아 테스트 DB 연결 상한을 넘지 않게 한다.
@DirtiesContext
@SpringBootTest
class LogFileLocationStartupTest {
    @MockitoSpyBean
    private LogFileLocationReporter logFileLocationReporter;

    @Test
    @DisplayName("애플리케이션 기동이 끝나면 로그 파일 경로 보고가 한 번 불린다")
    void reportsOnceWhenApplicationIsReady() {
        verify(logFileLocationReporter, times(1)).report();
    }
}
