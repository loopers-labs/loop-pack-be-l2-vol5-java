package com.loopers.application.shopping.command;

// 좋아요 등록·취소 커맨드 모음
public final class LikeCommand {
    private LikeCommand() {}

    public record Register(long userId, long productId) {}

    public record Cancel(long userId, long productId) {}
}
