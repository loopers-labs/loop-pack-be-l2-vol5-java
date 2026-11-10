package com.loopers.kafka;

import com.loopers.config.PurchaseActivityKafkaConfig;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.StreamerIntegrationTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * rebalance 방식 (W7, 학습자 요청 11-06). 구매 활동 listener의 consumer 설정 그대로 member 두 개를 띄운다.
 * <p>
 * eager(기본 RangeAssignor)는 member가 하나 들어올 때마다 <b>모든 member가 가진 partition을 전부 내려놓고</b>(revoke) 다시 받는다 —
 * 그동안 group 전체가 처리를 멈춘다(stop-the-world). cooperative(CooperativeStickyAssignor)는 <b>옮겨 갈 partition만</b> 내려놓고,
 * 나머지는 계속 가진 채 처리한다.
 * <p>
 * 이 테스트가 보지 않는 것: kill -9 된 consumer를 알아채는 시간(session.timeout.ms)은 assignor와 무관하다.
 */
class CooperativeRebalanceTest extends StreamerIntegrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    @Qualifier(PurchaseActivityKafkaConfig.RECORD_LISTENER)
    private ConcurrentKafkaListenerContainerFactory<String, String> purchaseActivityFactory;

    @DisplayName("member가 하나 들어와도 기존 member는 옮겨 갈 partition만 내려놓고, 나머지는 한 번도 놓지 않는다 (cooperative rebalance).")
    @Test
    void joiningMemberMovesOnlyThePartitionsItTakes() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "w7-coop-" + suffix;
        String group = "w7-coop-group-" + suffix;
        KafkaTestSupport.ensureTopic(topic, 3);
        List<Member> members = new ArrayList<>();
        try {
            // arrange: 첫 member가 partition 0·1·2를 모두 가진다
            Member first = Member.start("m1", topic, group, purchaseActivityFactory);
            members.add(first);
            await().atMost(WAIT).until(() -> first.assigned().size() == 3);
            first.resetHistory();

            // act: 두 번째 member가 들어온다
            Member second = Member.start("m2", topic, group, purchaseActivityFactory);
            members.add(second);
            await().atMost(WAIT).until(() -> !second.assigned().isEmpty() && first.assigned().size() + second.assigned().size() == 3);

            // assert: 첫 member가 내려놓은 partition = 두 번째 member가 가져간 partition 뿐
            assertThat(first.revokedSinceReset()).isEqualTo(second.assigned());
            // assert: 첫 member가 계속 가진 partition은 rebalance 동안 한 번도 손에서 떠나지 않았다
            assertThat(first.everEmptySinceReset()).isFalse();
        } finally {
            members.forEach(Member::stop);
        }
    }

    /** 자기 스레드에서 poll하는 group member 하나. 구매 활동 listener factory의 consumer 설정을 그대로 쓴다. */
    private static final class Member {

        private final KafkaConsumer<String, String> consumer;
        private final Set<Integer> assigned = ConcurrentHashMap.newKeySet();
        private final Set<Integer> revoked = ConcurrentHashMap.newKeySet();
        private volatile boolean everEmpty;
        private final Thread thread;

        private Member(String name, String topic, String group, ConcurrentKafkaListenerContainerFactory<String, String> factory) {
            Map<String, Object> props = new HashMap<>(factory.getConsumerFactory().getConfigurationProperties());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, "w7-coop-" + name);
            this.consumer = new KafkaConsumer<>(props);
            this.thread = new Thread(() -> run(topic), "w7-coop-" + name);
        }

        static Member start(String name, String topic, String group, ConcurrentKafkaListenerContainerFactory<String, String> factory) {
            Member member = new Member(name, topic, group, factory);
            member.thread.start();
            return member;
        }

        private void run(String topic) {
            try {
                consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        partitions.forEach(tp -> {
                            assigned.remove(tp.partition());
                            revoked.add(tp.partition());
                        });
                        if (assigned.isEmpty() && !partitions.isEmpty()) {
                            everEmpty = true;
                        }
                    }

                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        partitions.forEach(tp -> assigned.add(tp.partition()));
                    }

                    @Override
                    public void onPartitionsLost(Collection<TopicPartition> partitions) {
                        onPartitionsRevoked(partitions);
                    }
                });
                while (true) {
                    consumer.poll(Duration.ofMillis(100));
                }
            } catch (WakeupException ignored) {
                // stop
            } finally {
                consumer.close(Duration.ofSeconds(5));
            }
        }

        void resetHistory() {
            revoked.clear();
            everEmpty = false;
        }

        Set<Integer> assigned() {
            return new TreeSet<>(assigned);
        }

        Set<Integer> revokedSinceReset() {
            return new TreeSet<>(revoked);
        }

        boolean everEmptySinceReset() {
            return everEmpty;
        }

        void stop() {
            consumer.wakeup();
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
