package com.loopers.domain.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchaseActivityService {

    private final PurchaseActivityRepository purchaseActivityRepository;

    public void record(ConfirmedPurchase purchase) {
        purchaseActivityRepository.saveReceipt(purchase);
        purchaseActivityRepository.saveActivities(purchase);
    }
}
