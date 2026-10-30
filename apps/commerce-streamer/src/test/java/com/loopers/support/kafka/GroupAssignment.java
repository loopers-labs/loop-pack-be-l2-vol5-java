package com.loopers.support.kafka;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** broker 가 알려 준 group 배정. member(client id)마다 맡은 partition 번호를 담는다. */
public record GroupAssignment(Map<String, Set<Integer>> partitionsByMember) {

    /**
     * member 마다 맡은 partition 개수를 작은 순으로 나열한다. 누가 어느 partition 을 받는지는 보장되지 않아 개수만 본다.
     * 예: {@code [0, 1, 1]} 은 member 셋 중 하나는 아무것도 못 받고 둘이 한 개씩 맡았다는 뜻이다.
     */
    public List<Integer> partitionCounts() {
        return partitionsByMember.values().stream().map(Set::size).sorted().toList();
    }

    /** 누군가에게 배정된 partition 전체. */
    public Set<Integer> assignedPartitions() {
        return partitionsByMember.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));
    }

    public Set<Integer> partitionsOf(String clientId) {
        return partitionsByMember.getOrDefault(clientId, Set.of());
    }
}
