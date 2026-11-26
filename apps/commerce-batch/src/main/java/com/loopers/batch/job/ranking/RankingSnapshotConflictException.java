package com.loopers.batch.job.ranking;

/**
 * 이미 있는 snapshot과 다른 기간·revision으로 같은 snapshotId를 쓰려 하거나, 기존 revision 이하로 새 snapshot을 만들려 할 때 던진다.
 * 기존 입력을 덮어쓰지 않고 실패로 드러낸다.
 */
public class RankingSnapshotConflictException extends RuntimeException {
    public RankingSnapshotConflictException(String message) {
        super(message);
    }
}
