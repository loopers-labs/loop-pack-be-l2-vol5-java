package com.loopers.e2e;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 테스트가 직접 띄우고 닫는 두 앱의 context. 컨테이너(MySQL·Kafka·Redis)는 이 JVM 에서 처음 쓸 때 뜨고 JVM 이 끝나면 Testcontainers 가 정리한다.
 * 두 context 는 같은 DB·broker 를 보고, 각자 앱의 application.yml(이름만 바꾼 복사본)을 읽는다. 프로필은 test (gradle).
 */
public final class E2eContexts {

    private E2eContexts() {
    }

    /** 컨테이너를 띄우고 그 주소를 system property 로 둔다 (각 Config 의 static 블록). */
    public static void startContainers() {
        new MySqlTestContainersConfig();
        new RedisTestContainersConfig();
        KafkaTestContainersConfig.bootstrapServers();
    }

    /** commerce-api context. HTTP 는 임의 포트. extraSources 는 이번 실행에만 넣을 구성 (예: 보존한 W6 경로). */
    public static ConfigurableApplicationContext startApi(String topic, List<String> properties, Class<?>... extraSources) {
        List<Class<?>> sources = new ArrayList<>(List.of(E2eApiApplication.class));
        sources.addAll(List.of(extraSources));
        List<String> props = new ArrayList<>(List.of(
            "spring.config.name=e2e-commerce-api",
            "server.port=0",
            "management.server.port=0",
            "kafka.topics.order-events.name=" + topic
        ));
        props.addAll(properties);
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
            .web(WebApplicationType.SERVLET)
            .run(asArgs(props));
    }

    /** commerce-streamer 의 구매 활동 consumer context. 고유 topic·group 을 읽는다. 스키마는 건드리지 않는다. */
    public static ConfigurableApplicationContext startStreamer(String topic, String groupId) {
        return new SpringApplicationBuilder(E2eStreamerApplication.class)
            .web(WebApplicationType.NONE)
            .run(asArgs(List.of(
                "spring.config.name=e2e-commerce-streamer",
                "spring.application.name=commerce-streamer",
                "spring.jpa.hibernate.ddl-auto=none",
                "kafka.topics.order-events.name=" + topic,
                "kafka.consumers.purchase-activity.group-id=" + groupId
            )));
    }

    /** 명령행 인자로 넘긴다: yml(config data)보다 우선한다. builder.properties() 는 기본값이라 yml 에 덮인다. */
    private static String[] asArgs(List<String> properties) {
        return properties.stream().map(property -> "--" + property).toArray(String[]::new);
    }

    public static int port(ConfigurableApplicationContext api) {
        return Integer.parseInt(api.getEnvironment().getRequiredProperty("local.server.port"));
    }

    public static void closeQuietly(ConfigurableApplicationContext context) {
        if (context != null && context.isActive()) {
            context.close();
        }
    }
}
