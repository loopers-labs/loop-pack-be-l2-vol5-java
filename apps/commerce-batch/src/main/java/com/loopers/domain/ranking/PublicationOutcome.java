package com.loopers.domain.ranking;

public enum PublicationOutcome {
    /** 대상 snapshot 으로 pointer 를 만들거나 바꿨다. */
    PUBLISHED,
    /** 대상 snapshot 이 이미 공개돼 있다. */
    ALREADY_PUBLISHED,
    /** 같은 기간의 더 높은 revision 이 공개돼 있어 pointer 를 유지했다. 대상 완성 결과는 저장된다. */
    SKIPPED_NEWER
}
