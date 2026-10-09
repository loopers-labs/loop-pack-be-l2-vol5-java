package com.loopers.application.shopping.usecase;

import com.loopers.application.shopping.command.LikeCommand;

// 좋아요 취소 유스케이스
public interface CancelLikeUseCase {
    void execute(LikeCommand.Cancel command);
}
