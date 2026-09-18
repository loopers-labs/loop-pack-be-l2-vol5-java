package com.loopers.interfaces.api.auth;

/** 존재가 확인된 요청자. 컨트롤러는 이 타입으로만 요청자를 받는다 (설계 2.6). */
public record LoginUser(Long id) {}
