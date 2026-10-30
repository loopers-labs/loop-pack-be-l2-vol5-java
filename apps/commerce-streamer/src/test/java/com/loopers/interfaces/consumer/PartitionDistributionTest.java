package com.loopers.interfaces.consumer;

import com.loopers.support.kafka.ExperimentTopic;
import com.loopers.support.kafka.GroupAssignment;
import com.loopers.support.kafka.GroupMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실험 목적: <b>일이 어떻게 나뉘는가</b>. 같은 group 에서 consumer 를 늘리면 partition 이 어떻게 나뉘는지, 같은 key 가
 * 어느 partition 으로 가는지 실제 broker 에서 본다.
 *
 * <p>보이는 것: partition 수를 넘는 consumer 는 일을 맡지 못하고, 같은 key 는 같은 partition 으로 간다. 보이지 않는 것:
 * 서로 다른 key 가 다른 partition 으로 간다는 것, 여러 프로세스·여러 broker 에서의 동작. 관찰값은
 * {@code [W6-OBSERVATION]} 로그로 남긴다.
 */
class PartitionDistributionTest {

    private static final Logger log = LoggerFactory.getLogger(PartitionDistributionTest.class);

    @DisplayName("같은 group 의 consumer 를 1→2→3개로 늘리면 partition 2개가 한 member 씩 나뉘고 셋째는 놀며, 각 partition 의 record 는 소유자만 받는다.")
    @Test
    void assignsEachPartitionToOneMember_andLeavesExtraMemberIdle() {
        // arrange: 숫자는 member 마다 맡은 partition 개수다. [0, 1, 1] 은 하나는 놀고 둘이 한 개씩 맡았다는 뜻이다.
        ExperimentTopic topic = ExperimentTopic.create("assign", 2);
        List<List<Integer>> expectedPartitionCounts = List.of(List.of(2), List.of(1, 1), List.of(0, 1, 1));
        List<GroupMember> members = new ArrayList<>();

        try {
            // act 1: consumer 를 한 개씩 늘리며 실제 배정이 안정될 때까지 기다린다
            for (List<Integer> expected : expectedPartitionCounts) {
                members.add(topic.join("member-" + (members.size() + 1)));
                GroupAssignment assignment = topic.awaitAssignment(expected);
                log.info("[W6-OBSERVATION] consumers={} assignment={}", members.size(), assignment.partitionsByMember());
            }

            // act 2: 두 partition 에 test record 를 명시적으로 보낸다(업무 topic 이 아니라 가짜 구매 활동이 생기지 않는다)
            topic.send(0, "key", "to-partition-0");
            topic.send(1, "key", "to-partition-1");
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(members.stream().mapToInt(m -> m.received().size()).sum()).isEqualTo(2));

            // assert: member 는 자기 partition 의 record 만 받았고, 배정 없는 member 는 아무것도 받지 않았다
            GroupAssignment assignment = topic.assignment();
            for (GroupMember member : members) {
                assertThat(assignment.partitionsOf(member.clientId())).containsAll(member.receivedPartitions());
                if (assignment.partitionsOf(member.clientId()).isEmpty()) {
                    assertThat(member.received()).isEmpty();
                }
                log.info("[W6-OBSERVATION] {} assigned={} receivedPartitions={}",
                    member.clientId(), assignment.partitionsOf(member.clientId()), member.receivedPartitions());
            }
        } finally {
            members.forEach(GroupMember::close);
        }
    }

    @DisplayName("같은 key 로 여러 번 보내면 실제 전송 metadata 의 partition 이 항상 같다.")
    @Test
    void sendsSameKeyToSamePartition() {
        // arrange
        ExperimentTopic topic = ExperimentTopic.create("key", 2);

        for (String key : List.of("42", "43")) {
            // act
            Set<Integer> partitions = IntStream.range(0, 5)
                .mapToObj(i -> topic.send(key, "event-" + i).partition())
                .collect(Collectors.toSet());

            // assert: 서로 다른 key 가 다른 partition 으로 간다고는 단정하지 않는다
            assertThat(partitions).hasSize(1);
            log.info("[W6-OBSERVATION] key={} partitions={}", key, partitions);
        }
    }
}
