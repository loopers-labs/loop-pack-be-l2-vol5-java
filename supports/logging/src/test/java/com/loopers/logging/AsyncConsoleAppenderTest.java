package com.loopers.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConsoleAppenderTest {

    private LoggerContext context;
    private AsyncAppender async;
    private ListAppender<ILoggingEvent> written;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configure() throws Exception {
        context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (InputStream config = getClass().getResourceAsStream("/async-console-test.xml")) {
            configurator.doConfigure(config);
        }
        Appender<ILoggingEvent> root = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC-CONSOLE");
        async = (AsyncAppender) root;
        written = async == null ? null : (ListAppender<ILoggingEvent>) async.getAppender("CONSOLE");
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    @DisplayName("콘솔 출력은 큐를 거쳐 따로 쓰고, 큐가 차도 줄을 버리지 않고 기다린다")
    @Test
    void queuesWithoutDiscarding() {
        assertThat(async).isNotNull();
        assertThat(async.getDiscardingThreshold()).isZero();
        assertThat(async.isNeverBlock()).isFalse();
        assertThat(async.getQueueSize()).isGreaterThanOrEqualTo(8192);
        assertThat(async.isIncludeCallerData()).isFalse();
    }

    @DisplayName("큐를 거친 줄도 식별자 필드를 그대로 갖는다")
    @Test
    void keepsFields() {
        context.getLogger("payment").atInfo()
            .addKeyValue("event", "payment_status_changed")
            .addKeyValue("orderId", 14L)
            .log("결제 상태가 바뀌었다: orderId={}", 14L);

        awaitWritten(1);

        Map<String, Object> fields = new HashMap<>();
        written.list.get(0).getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        assertThat(fields).containsEntry("event", "payment_status_changed").containsEntry("orderId", 14L);
        assertThat(written.list.get(0).getFormattedMessage()).isEqualTo("결제 상태가 바뀌었다: orderId=14");
    }

    @DisplayName("정상 종료하면 큐에 남은 줄을 다 쓰고 끝난다")
    @Test
    void flushesOnStop() {
        Logger logger = context.getLogger("payment");
        for (int i = 0; i < 5_000; i++) {
            logger.info("line {}", i);
        }

        context.stop();

        assertThat(written.list).hasSize(5_000);
    }

    @DisplayName("dev, qa, prd 는 큐를 거친 출력을 쓰고, 출력을 바로 읽는 local 과 test 는 직접 쓴다")
    @Test
    void appliesPerProfile() throws Exception {
        Document config;
        try (InputStream logback = getClass().getResourceAsStream("/logback/logback.xml")) {
            config = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(logback);
        }
        NodeList profiles = config.getElementsByTagName("springProfile");
        Map<String, String> rootAppender = new HashMap<>();
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            Element root = (Element) profile.getElementsByTagName("root").item(0);
            Element reference = (Element) root.getElementsByTagName("appender-ref").item(0);
            rootAppender.put(profile.getAttribute("name"), reference.getAttribute("ref"));
        }

        assertThat(rootAppender)
            .containsEntry("dev", "ASYNC-CONSOLE")
            .containsEntry("qa", "ASYNC-CONSOLE")
            .containsEntry("prd", "ASYNC-CONSOLE")
            .containsEntry("local,local-dev,test", "CONSOLE");
    }

    private void awaitWritten(int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (written.list.size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(written.list).hasSizeGreaterThanOrEqualTo(count);
    }
}
