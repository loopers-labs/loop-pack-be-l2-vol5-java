package com.loopers.domain.coupon.vo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * DB 접수 기록으로 다시 만들 Redis 사본 (8주차 D-174).
 * 주인 없는 슬롯(FAILED 가 남겼는데 WAITING · ISSUED 가 갖지 않은 슬롯)은 가장 앞 예비로 다시 채운다.
 * 승격은 발급될 때만 DB 에 남아, 승격됐지만 아직 발급 전인 사람은 DB 에 RESERVE 로 남기 때문이다
 *
 * @param users    사용자 id → 저장값("상태:순번", ISSUED 는 "ISSUED:순번:couponId")
 * @param queue    대기열에 넣을 WAITING 항목, 순번 순
 * @param reserve  예비 목록(사용자 id → 순번)
 */
public record RebuildPlan(long maxSequence, Map<Long, String> users, List<Queued> queue, Map<Long, Long> reserve,
                          long promotedCount, boolean soldOut) {

    public record Queued(long userId, long sequence, int slotNo) {}

    /** @param rows 순번 순 */
    public static RebuildPlan of(List<IssueRequestRow> rows, boolean open, boolean soldOut) {
        Map<Long, String> users = new LinkedHashMap<>();
        List<Queued> queue = new ArrayList<>();
        Deque<IssueRequestRow> reserves = new ArrayDeque<>();
        Set<Integer> heldSlots = new HashSet<>();
        TreeSet<Integer> failedSlots = new TreeSet<>();
        long maxSequence = 0;
        long promoted = 0;
        for (IssueRequestRow row : rows) {
            maxSequence = Math.max(maxSequence, row.sequence());
            if (row.promoted()) {
                promoted++;
            }
            switch (row.status()) {
                case "WAITING" -> {
                    users.put(row.userId(), "WAITING:" + row.sequence());
                    queue.add(new Queued(row.userId(), row.sequence(), row.slotNo()));
                    heldSlots.add(row.slotNo());
                }
                case "ISSUED" -> {
                    users.put(row.userId(), "ISSUED:" + row.sequence() + ":" + row.couponId());
                    heldSlots.add(row.slotNo());
                }
                case "FAILED" -> {
                    users.put(row.userId(), "FAILED:" + row.sequence());
                    if (row.slotNo() != null) {
                        failedSlots.add(row.slotNo());
                    }
                }
                case "RESERVE" -> reserves.add(row);
                default -> throw new IllegalStateException("모르는 접수 상태. userId=" + row.userId() + " status=" + row.status());
            }
        }
        failedSlots.removeAll(heldSlots);
        if (open && !soldOut) {
            for (Integer slot : failedSlots) {
                IssueRequestRow next = reserves.pollFirst();
                if (next == null) {
                    break;
                }
                users.put(next.userId(), "WAITING:" + next.sequence());
                queue.add(new Queued(next.userId(), next.sequence(), slot));
                promoted++;
            }
        }
        Map<Long, Long> reserve = new LinkedHashMap<>();
        for (IssueRequestRow row : reserves) {
            users.put(row.userId(), "RESERVE:" + row.sequence());
            reserve.put(row.userId(), row.sequence());
        }
        return new RebuildPlan(maxSequence, users, queue, reserve, promoted, soldOut);
    }
}
