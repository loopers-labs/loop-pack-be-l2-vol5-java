package com.loopers.interfaces.consumer;

class UnsupportedSchemaVersionException extends RuntimeException {

    UnsupportedSchemaVersionException(int schemaVersion) {
        super("이 소비자가 모르는 OrderConfirmed schemaVersion 이다: " + schemaVersion);
    }
}
