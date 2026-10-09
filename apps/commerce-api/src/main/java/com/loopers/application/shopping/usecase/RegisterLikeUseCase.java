package com.loopers.application.shopping.usecase;

import com.loopers.application.shopping.command.LikeCommand;

// 좋아요 등록 유스케이스
public interface RegisterLikeUseCase {
    void execute(LikeCommand.Register command);
}
