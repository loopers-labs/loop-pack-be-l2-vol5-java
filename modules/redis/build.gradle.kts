plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.redisson:redisson:${project.properties["redissonVersion"]}")

    testFixturesImplementation("com.redis:testcontainers-redis")
}
