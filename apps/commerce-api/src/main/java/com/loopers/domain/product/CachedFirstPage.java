package com.loopers.domain.product;

/** 캐시 읽기·채우기·삭제·키가 모두 같은 범위를 써야 하므로 한 곳에서만 정의한다. */
public final class CachedFirstPage {
    public static final int PAGE = 0;
    public static final int SIZE = 20;

    private CachedFirstPage() {}

    public static boolean matches(int page, int size) {
        return page == PAGE && size == SIZE;
    }
}
