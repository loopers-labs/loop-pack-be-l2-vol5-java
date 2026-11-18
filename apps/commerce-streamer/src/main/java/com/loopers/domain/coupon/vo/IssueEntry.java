package com.loopers.domain.coupon.vo;

/**
 * 대기열에서 꺼낸 발급 항목 하나. slotNo 는 순번이거나 승격 때 물려받은 슬롯이다 (8주차 D-119, D-141).
 * 시도 정보는 항목 내용으로 들고 다닌다. 지연 ZSET 을 거쳐 돌아오면 새 ID 를 받기 때문이다 (8주차 D-129)
 *
 * @param unclassified 미분류 예외로 끝난 횟수 (8주차 D-127)
 * @param transientAttempts 일시 오류로 재전달된 횟수. backoff 계산에만 쓴다 (8주차 D-126)
 * @param deliveries PEL 전달 횟수. poison 판정에 쓴다 (8주차 D-173)
 */
public record IssueEntry(long campaignId, String entryId, long userId, long sequence, int slotNo, String epoch,
                         int unclassified, int transientAttempts, long deliveries) {

    public IssueEntry withUnclassified() {
        return new IssueEntry(campaignId, entryId, userId, sequence, slotNo, epoch, unclassified + 1, transientAttempts, deliveries);
    }

    public IssueEntry withTransient() {
        return new IssueEntry(campaignId, entryId, userId, sequence, slotNo, epoch, unclassified, transientAttempts + 1, deliveries);
    }
}
