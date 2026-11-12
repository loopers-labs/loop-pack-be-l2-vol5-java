package com.loopers.support.order;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 실제 앱을 별도 JVM으로 띄운다. 두 앱의 component scan이 섞이지 않고, 제품 의존성도 생기지 않는다. */
public final class SpringAppProcess implements AutoCloseable {
    private static final Duration STOP_WAIT = Duration.ofSeconds(30);

    private final Process process;
    private final Path log;

    private SpringAppProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    public record Database(String jdbcUrl, String username, String password) {
        public static Database fromSystemProperties() {
            return new Database(System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
                System.getProperty("datasource.mysql-jpa.main.username"),
                System.getProperty("datasource.mysql-jpa.main.password"));
        }
    }

    public static SpringAppProcess streamer(Database database, String bootstrapServers, String topic) throws IOException {
        return streamer(database, bootstrapServers, topic, List.of());
    }

    /** 추가 인자(consumer 재시도 예산 등)를 넘겨 구매 활동 앱을 띄운다. */
    public static SpringAppProcess streamer(Database database, String bootstrapServers, String topic, List<String> arguments)
        throws IOException {
        List<String> command = new ArrayList<>(List.of(java(), "-Duser.timezone=Asia/Seoul",
            "-cp", required("e2e.streamerClasspath"), "com.loopers.CommerceStreamerApplication"));
        command.addAll(commonArguments(database, bootstrapServers, topic));
        command.add("--commerce.order-events.consumer.enabled=true");
        command.add("--commerce.order-events.consumer.concurrency=3");
        command.addAll(arguments);
        return start("commerce-streamer", command);
    }

    /** W6 revision으로 빌드한 실행 jar를 띄운다. */
    public static SpringAppProcess w6Api(Database database, String bootstrapServers, String topic, int port, String pgUrl)
        throws IOException {
        return w6Api(database, bootstrapServers, topic, port, pgUrl, List.of());
    }

    /** W6 실행물에 추가 인자(producer 설정 등)를 넘겨 띄운다. */
    public static SpringAppProcess w6Api(Database database, String bootstrapServers, String topic, int port, String pgUrl,
                                         List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(java(), "-Duser.timezone=Asia/Seoul", "-jar", required("w6.commerceApiJar")));
        command.addAll(apiArguments(database, bootstrapServers, topic, port, pgUrl));
        command.addAll(arguments);
        return start("commerce-api-w6", command);
    }

    /** 현재 코드의 commerce-api를 띄운다. 추가 인자로 relay와 복구 대상을 켠다. */
    public static SpringAppProcess currentApi(Database database, String bootstrapServers, String topic, int port, String pgUrl,
                                              List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(java(), "-Duser.timezone=Asia/Seoul",
            "-cp", required("e2e.apiClasspath"), "com.loopers.CommerceApiApplication"));
        command.addAll(apiArguments(database, bootstrapServers, topic, port, pgUrl));
        command.addAll(arguments);
        return start("commerce-api-current", command);
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public boolean logContains(String text) throws IOException {
        return Files.readString(log).contains(text);
    }

    /** 운영자가 로그에서 세 볼 만한 문구가 몇 줄에 나왔는지 센다. */
    public long logLineCount(String text) throws IOException {
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.contains(text)).count();
        }
    }

    /** 실패한 단계의 원인을 보여 줄 수 있게 로그 끝부분을 돌려준다. */
    public String logTail() {
        try {
            List<String> lines = Files.readAllLines(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException exception) {
            return "앱 로그를 읽지 못했습니다: " + exception.getMessage();
        }
    }

    /** SIGKILL로 즉시 종료한다. shutdown hook이나 남은 전송 처리가 실행되지 않는다. */
    public void kill() throws InterruptedException {
        process.destroyForcibly().waitFor(STOP_WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(STOP_WAIT.toSeconds(), TimeUnit.SECONDS)) {
            kill();
        }
        Files.deleteIfExists(log);
    }

    private static SpringAppProcess start(String name, List<String> command) throws IOException {
        Path log = Files.createTempFile(name, ".log");
        return new SpringAppProcess(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start(), log);
    }

    private static List<String> apiArguments(Database database, String bootstrapServers, String topic, int port, String pgUrl) {
        List<String> arguments = new ArrayList<>(commonArguments(database, bootstrapServers, topic));
        arguments.set(arguments.indexOf("--server.port=0"), "--server.port=" + port);
        arguments.add("--commerce.order-events.enabled=true");
        arguments.add("--commerce.payment.pg.base-url=" + pgUrl);
        return arguments;
    }

    private static List<String> commonArguments(Database database, String bootstrapServers, String topic) {
        return List.of("--spring.profiles.active=test", "--server.port=0", "--management.server.port=0",
            "--spring.jpa.show-sql=false", "--spring.jpa.hibernate.ddl-auto=update",
            "--spring.kafka.bootstrap-servers=" + bootstrapServers, "--commerce.order-events.topic=" + topic,
            "--datasource.mysql-jpa.main.jdbc-url=" + database.jdbcUrl(),
            "--datasource.mysql-jpa.main.username=" + database.username(),
            "--datasource.mysql-jpa.main.password=" + database.password());
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Gradle 테스트 태스크의 " + property + "가 필요합니다.");
        }
        return value;
    }
}
