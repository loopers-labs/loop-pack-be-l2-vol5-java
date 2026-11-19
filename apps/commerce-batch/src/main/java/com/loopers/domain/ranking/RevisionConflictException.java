package com.loopers.domain.ranking;

/** 같은 기간에 건너뛰거나 이미 쓴 revision 으로 새 snapshot 을 만들려 했다. */
public class RevisionConflictException extends RuntimeException {

    public RevisionConflictException(String message) {
        super(message);
    }
}
