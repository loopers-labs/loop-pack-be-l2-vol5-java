package com.loopers.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import java.nio.file.Path;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 로그 수집기가 읽는 경로와 앱이 쓰는 경로가 어긋나면 로그 부재를 사고 없음으로 오판한다(w10s7).
// 설정 값이 아니라 초기화된 root의 파일 appender가 실제로 연 경로를 남긴다. 파일 로그가 꺼진 프로필에서는 남기지 않는다.
@Slf4j
@Component
public class LogFileLocationReporter {
    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders(); appenders.hasNext(); ) {
            if (appenders.next() instanceof FileAppender<ILoggingEvent> fileAppender && fileAppender.getFile() != null) {
                log.info("애플리케이션 로그 파일 경로. appender={}, path={}", fileAppender.getName(),
                    Path.of(fileAppender.getFile()).toAbsolutePath().normalize());
            }
        }
    }
}
