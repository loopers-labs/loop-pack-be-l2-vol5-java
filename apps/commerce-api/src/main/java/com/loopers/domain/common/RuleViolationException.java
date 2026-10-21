package com.loopers.domain.common;

public class RuleViolationException extends IllegalStateException {
    public RuleViolationException(String message) { super(message); }
    public RuleViolationException(String message, Throwable cause) { super(message, cause); }
}
