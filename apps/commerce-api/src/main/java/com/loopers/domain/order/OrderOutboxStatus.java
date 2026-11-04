package com.loopers.domain.order;

/**
 * 주문 확정 Outbox 의 전달 상태(설계 W7.2-4·W7.3-3). 소비 완료 표지가 아니다.
 * PENDING 은 자동 조회 대상, PUBLISHED 는 broker 성공 확인을 저장한 상태, BLOCKED 는 자동 시도 중단·미해결이다.
 * 이름 문자열 VARCHAR(16) 로 저장하며 native ENUM·값 CHECK 를 두지 않는다.
 */
public enum OrderOutboxStatus {
    PENDING,
    PUBLISHED,
    BLOCKED
}
