package com.loopers.support;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.assertj.core.api.ThrowableAssert;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class ErrorAssertions {
    private ErrorAssertions() {}

    /** 호출이 주어진 ErrorType 의 CoreException 을 던지는지 확인한다. */
    public static void assertThrowsErrorType(ThrowableAssert.ThrowingCallable callable, ErrorType expected) {
        assertThatThrownBy(callable)
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(expected);
    }
}
