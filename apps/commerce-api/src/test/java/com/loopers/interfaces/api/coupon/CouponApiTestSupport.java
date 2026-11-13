package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.infrastructure.coupon.CouponCampaignJpaEntity;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponJpaRepository;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Removes only data that the test itself inserted. */
abstract class CouponApiTestSupport {
    @Autowired protected CouponCampaignJpaRepository campaigns;
    @Autowired protected CouponJpaRepository coupons;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;

    private final List<Long> createdCampaigns = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();

    protected long activeCampaignWithNoIssues(long quota) {
        var row = campaigns.saveAndFlush(new CouponCampaignJpaEntity("실습 행사", true, quota, "5,000원 할인권"));
        createdCampaigns.add(row.getId());
        return row.getId();
    }

    protected long user() {
        long id = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
        jdbc.update("insert into users (id) values (?)", id);
        createdUsers.add(id);
        return id;
    }

    protected ResponseEntity<JsonNode> list(long userId) {
        return rest.exchange("/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(headers(userId)), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> submit(long campaignId, long userId) {
        return rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/issue-requests", HttpMethod.POST,
                new HttpEntity<>(headers(userId)), JsonNode.class);
    }

    protected static HttpHeaders headers(long userId) {
        var headers = new HttpHeaders();
        headers.set("X-USER-ID", Long.toString(userId));
        return headers;
    }

    @AfterEach
    void removeOnlyTestData() {
        for (long campaignId : createdCampaigns) {
            coupons.deleteByCampaignId(campaignId);
            campaigns.deleteById(campaignId);
        }
        for (long userId : createdUsers) {
            jdbc.update("delete from users where id = ?", userId);
        }
        createdCampaigns.clear();
        createdUsers.clear();
    }
}
