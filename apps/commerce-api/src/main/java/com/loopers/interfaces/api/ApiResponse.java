package com.loopers.interfaces.api;

public record ApiResponse<T>(Metadata meta, T data) {
    public record Metadata(Result result, String errorCode, String message) {
        public enum Result {
            SUCCESS, FAIL
        }

        public static Metadata success() {
            return new Metadata(Result.SUCCESS, null, null);
        }

        public static Metadata success(String message) {
            return new Metadata(Result.SUCCESS, null, message);
        }

        public static Metadata fail(String errorCode, String errorMessage) {
            return new Metadata(Result.FAIL, errorCode, errorMessage);
        }
    }

    public static ApiResponse<Object> success() {
        return new ApiResponse<>(Metadata.success(), null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(Metadata.success(), data);
    }

    /** 성공이지만 요청자에게 알릴 말이 있을 때 쓴다. 예: 202 접수는 발급 보장이 아니라는 안내 (W8 §8-1) */
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(Metadata.success(message), data);
    }

    public static ApiResponse<Object> fail(String errorCode, String errorMessage) {
        return new ApiResponse<>(
            Metadata.fail(errorCode, errorMessage),
            null
        );
    }
}
