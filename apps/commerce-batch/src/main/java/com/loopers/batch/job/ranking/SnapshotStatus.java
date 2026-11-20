package com.loopers.batch.job.ranking;

/**
 * snapshot 상태 (설계 10-5). 입력 고정 TX 가 FROZEN 으로 만들고, 공개 TX 가 한 번만 바꾼다. 계산 진행은 Batch metadata 로 본다.
 * <pre>
 * (고정) → FROZEN ─공개 TX: pointer 를 이 revision 으로→ PUBLISHED
 *                 └공개 TX: 더 새 revision 이 이미 공개→ SUPERSEDED
 * </pre>
 */
public enum SnapshotStatus {
    /** 입력이 고정됐다. 점수 계산 중이거나 계산·공개가 실패했다. 다음 revision 을 막는다. */
    FROZEN,
    /** 완성 ranking 을 만들고 pointer 를 이 snapshot 으로 바꿨다. */
    PUBLISHED,
    /** 완성 ranking 은 만들었지만 더 새 revision 이 이미 공개돼 pointer 를 바꾸지 않았다. */
    SUPERSEDED
}
