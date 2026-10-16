package com.loopers.support.cache;

import java.util.List;
import java.util.stream.Collectors;

/** Cache names and canonical API keys. Revision suffixes isolate late fills; fixed v4 keys are used only by the historical experiment. */
public final class CacheKeys {
    public static final String VERSION_PREFIX = "v4:";
    public static final String PRODUCTS = "products";
    public static final String RANKINGS = "rankings";
    public static final String BRANDS = "brands";
    public static final String MY_LIKES = "my-likes";
    public static final String COUNTS = "like-counts";
    public static final String STOCKS = "stocks";
    public static final String CATALOG_GROUP = "catalog";
    public static final String STOCK_GROUP = "stock";
    public static final List<String> NAMES = List.of(PRODUCTS, RANKINGS, BRANDS, MY_LIKES, COUNTS, STOCKS);
    private CacheKeys() { }
    public static String products(String sort, Long brand, int page, int size) {
        return VERSION_PREFIX + "products?sort=" + sort + "&brand=" + (brand == null ? "all" : brand) + page(page, size);
    }
    public static String product(Long id) { return VERSION_PREFIX + "products/" + id; }
    public static String brands(int page, int size) { return VERSION_PREFIX + "admin:brands?" + page(page, size).substring(1); }
    public static String customerBrands(int page, int size) { return VERSION_PREFIX + "brands?" + page(page, size).substring(1); }
    public static String myLikes(Long user, int page, int size) { return VERSION_PREFIX + "users/" + user + "/likes?" + page(page, size).substring(1); }
    public static String userGroup(Long user) { return "likes:" + user; }
    public static String item(String bucket, Long id) { return VERSION_PREFIX + "products/" + id + "/" + bucket; }
    public static String stockGroup(Long id) { return STOCK_GROUP + ":" + id; }
    public static String batch(String bucket, List<Long> ids) {
        return VERSION_PREFIX + "products/" + bucket + "?ids=" + ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
    public static String[] groups(String bucket, String key) {
        if (MY_LIKES.equals(bucket)) return new String[]{CATALOG_GROUP, userGroup(Long.valueOf(key.split("/")[1]))};
        if (STOCKS.equals(bucket)) return new String[]{CATALOG_GROUP, stockGroup(Long.valueOf(key.split("/")[1]))};
        return new String[]{CATALOG_GROUP};
    }
    private static String page(int page, int size) { return "&page=" + page + "&size=" + size; }
}
