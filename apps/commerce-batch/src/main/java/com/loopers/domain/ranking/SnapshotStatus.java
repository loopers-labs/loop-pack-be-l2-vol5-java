package com.loopers.domain.ranking;

public enum SnapshotStatus {
    /** 입력을 고정했고 아직 공개하지 않았다. */
    FROZEN,
    /** 완성본을 공개했다. 현재 pointer 인지는 기간별 공개 행이 정한다. */
    PUBLISHED,
    /** 계산은 끝났지만 더 새 revision 이 이미 공개돼 있어 공개하지 않았다. */
    SUPERSEDED
}
