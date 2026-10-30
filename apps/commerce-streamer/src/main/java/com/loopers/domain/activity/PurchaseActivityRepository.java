package com.loopers.domain.activity;

public interface PurchaseActivityRepository {

    void saveReceipt(ConfirmedPurchase purchase);

    void saveActivities(ConfirmedPurchase purchase);
}
