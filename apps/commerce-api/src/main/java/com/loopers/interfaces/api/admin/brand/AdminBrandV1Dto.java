package com.loopers.interfaces.api.admin.brand;

public class AdminBrandV1Dto {
    public record CreateRequest(String name) {}

    public record UpdateRequest(String name) {}
}
