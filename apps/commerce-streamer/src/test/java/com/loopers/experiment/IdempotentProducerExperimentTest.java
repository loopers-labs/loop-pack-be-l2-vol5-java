package com.loopers.experiment;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Latency;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 블로그 "멱등 Producer란 정말 존재하는가?"(docs/study/blog-idempotent-producer.md)의 실험. 제품 코드를 쓰지 않고 kafka-clients
 * producer만 직접 몬다.
 *
 * <ol>
 *   <li>멱등 producer가 막는 장면: broker는 기록했는데 응답만 늦게 오게 해 producer 내부 재시도를 일으킨다. 멱등 on/off를 비교한다.</li>
 *   <li>멱등 producer가 못 막는 장면: 같은 producer가 같은 이벤트로 {@code send()}를 다시 부른다(relay 재발행과 같은 모양).</li>
 *   <li>transaction도 못 막는 장면: 같은 transactional.id로 재시작한 producer가 이미 commit된 이벤트를 다시 보낸다. 좀비는 막힌다.</li>
 * </ol>
 * 결과는 consumer로 센 record 수와 broker 로그 파일({@code kafka-dump-log.sh})의 producerId·epoch·sequence로 본다.
 * {@code @Tag("experiment")}라 check에서 빠지고 {@code ./gradlew :apps:commerce-streamer:experimentTest}로만 돈다.
 */
@Tag("experiment")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotentProducerExperimentTest {

    private static final int PROXY_PORT = 8666;
    private static final String EVENT_KEY = "1";
    private static final String EVENT_VALUE = "order-confirmed:v1:1";

    private Network network;
    private ToxiproxyContainer toxiproxy;
    private KafkaContainer kafka;
    private Proxy proxy;

    @BeforeAll
    void startContainers() throws Exception {
        network = Network.newNetwork();
        toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0").withNetwork(network);
        toxiproxy.start();
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"))
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withListener("kafka:19092", () -> toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(PROXY_PORT));
        kafka.start();
        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        proxy = client.createProxy("kafka", "0.0.0.0:" + PROXY_PORT, "kafka:19092");
    }

    @AfterAll
    void stopContainers() {
        kafka.stop();
        toxiproxy.stop();
        network.close();
    }

    @DisplayName("실험 1. broker가 기록한 뒤 응답만 늦으면 producer가 같은 묶음을 재시도한다. 멱등 off면 재시도마다 한 건씩 더 쌓이고, "
            + "멱등 on이면 broker가 같은 sequence를 알아보고 한 건만 남긴다.")
    @Test
    void retryAfterLostResponse() throws Exception {
        RetryResult off = sendWithSlowResponse(false);
        RetryResult on = sendWithSlowResponse(true);

        System.out.println("[EXP1] idempotence=false: " + off);
        System.out.println("[EXP1] idempotence=true : " + on);
        printLog("EXP1 idempotence=false", off.topic());
        printLog("EXP1 idempotence=true", on.topic());

        assertThat(off.copies()).isGreaterThanOrEqualTo(2);
        assertThat(on.copies()).isEqualTo(1);
    }

    @DisplayName("실험 2. 멱등 producer 하나가 같은 이벤트로 send()를 두 번 부르면, 같은 producerId에 sequence만 다른 두 묶음으로 둘 다 쌓인다.")
    @Test
    void resendBySameProducer() throws Exception {
        String topic = newTopic("exp2-resend");
        List<Long> offsets = new ArrayList<>();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps(kafka.getBootstrapServers(), true))) {
            offsets.add(producer.send(new ProducerRecord<>(topic, EVENT_KEY, EVENT_VALUE)).get(10, TimeUnit.SECONDS).offset());
            offsets.add(producer.send(new ProducerRecord<>(topic, EVENT_KEY, EVENT_VALUE)).get(10, TimeUnit.SECONDS).offset());
        }
        int copies = countCopies(topic, "read_uncommitted");

        System.out.println("[EXP2] send offsets=" + offsets + ", copies=" + copies);
        printLog("EXP2", topic);

        assertThat(copies).isEqualTo(2);
    }

    @DisplayName("실험 3. 같은 transactional.id로 다시 띄운 producer는 좀비의 쓰기를 막는다(fencing). 하지만 이미 commit된 이벤트를 "
            + "다시 보내면 read_committed로 읽어도 두 건이다.")
    @Test
    void transactionalResendAfterRestart() throws Exception {
        String topic = newTopic("exp3-txn");
        String transactionalId = "relay-" + UUID.randomUUID();

        KafkaProducer<String, String> first = new KafkaProducer<>(transactionalProps(transactionalId));
        first.initTransactions();
        first.beginTransaction();
        long firstOffset = first.send(new ProducerRecord<>(topic, EVENT_KEY, EVENT_VALUE)).get(10, TimeUnit.SECONDS).offset();
        first.commitTransaction();
        // 여기서 relay가 "PUBLISHED" 저장 전에 죽었다고 본다. first는 닫지 않은 채 좀비로 남는다.

        KafkaProducer<String, String> restarted = new KafkaProducer<>(transactionalProps(transactionalId));
        restarted.initTransactions();

        String zombieResult;
        try {
            first.beginTransaction();
            first.send(new ProducerRecord<>(topic, EVENT_KEY, "zombie:" + EVENT_VALUE));
            first.commitTransaction();
            zombieResult = "committed";
        } catch (KafkaException e) {
            zombieResult = e.getClass().getSimpleName();
        } finally {
            first.close(Duration.ofSeconds(5));
        }

        restarted.beginTransaction();
        long resendOffset = restarted.send(new ProducerRecord<>(topic, EVENT_KEY, EVENT_VALUE)).get(10, TimeUnit.SECONDS).offset();
        restarted.commitTransaction();
        restarted.close(Duration.ofSeconds(5));

        int committedCopies = countCopies(topic, "read_committed");
        int zombieCopies = countValue(topic, "read_committed", "zombie:" + EVENT_VALUE);

        System.out.println("[EXP3] firstCommitOffset=" + firstOffset + ", zombie=" + zombieResult + ", resendOffsetAfterRestart=" + resendOffset
                + ", readCommittedCopies=" + committedCopies + ", zombieCopies=" + zombieCopies);
        printLog("EXP3", topic);

        assertThat(committedCopies).isEqualTo(2);
        assertThat(zombieCopies).isZero();
    }

    private RetryResult sendWithSlowResponse(boolean idempotent) throws Exception {
        String topic = newTopic(idempotent ? "exp1-on" : "exp1-off");
        String viaProxy = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(PROXY_PORT);
        Map<String, Object> props = producerProps(viaProxy, idempotent);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_500);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 200);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, "warmup", "warmup")).get(10, TimeUnit.SECONDS);

            Latency slow = proxy.toxics().latency("slow-response", ToxicDirection.DOWNSTREAM, 3_000);
            long started = System.nanoTime();
            Future<RecordMetadata> future = producer.send(new ProducerRecord<>(topic, EVENT_KEY, EVENT_VALUE));
            Thread.sleep(4_000);
            slow.remove();
            RecordMetadata metadata = future.get(30, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            return new RetryResult(topic, idempotent, metadata.offset(), elapsedMs, countCopies(topic, "read_uncommitted"));
        }
    }

    private Map<String, Object> producerProps(String bootstrap, boolean idempotent) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, idempotent);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return props;
    }

    private Map<String, Object> transactionalProps(String transactionalId) {
        Map<String, Object> props = producerProps(kafka.getBootstrapServers(), true);
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        return props;
    }

    private String newTopic(String prefix) throws Exception {
        String topic = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        return topic;
    }

    private int countCopies(String topic, String isolation) {
        return countValue(topic, isolation, EVENT_VALUE);
    }

    private int countValue(String topic, String isolation, String value) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolation);
        TopicPartition tp = new TopicPartition(topic, 0);
        int count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.assign(List.of(tp));
            consumer.seekToBeginning(List.of(tp));
            long end = consumer.endOffsets(List.of(tp)).get(tp);
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumer.position(tp) < end && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (value.equals(record.value())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void printLog(String label, String topic) throws Exception {
        Container.ExecResult result = kafka.execInContainer("sh", "-c",
                "/opt/kafka/bin/kafka-dump-log.sh --files $(find / -type d -name '" + topic + "-0' 2>/dev/null | head -1)/00000000000000000000.log");
        System.out.println("[" + label + " log file] " + topic);
        result.getStdout().lines()
                .filter(line -> line.startsWith("baseOffset"))
                .forEach(line -> System.out.println("  " + line));
        if (!result.getStderr().isBlank()) {
            System.out.println("  stderr: " + result.getStderr().strip());
        }
    }

    private record RetryResult(String topic, boolean idempotent, long returnedOffset, long elapsedMs, int copies) {
        @Override
        public String toString() {
            return "topic=" + topic + ", returnedOffset=" + returnedOffset + ", elapsed=" + elapsedMs + "ms, copies=" + copies;
        }
    }
}
