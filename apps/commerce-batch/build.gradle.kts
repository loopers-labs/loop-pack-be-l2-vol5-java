dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.testcontainers:mysql")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

// Reuse the consumer's authoritative source DDL without depending on the streamer app's Java classes.
tasks.processTestResources {
    from(project(":apps:commerce-streamer").file("src/main/resources/db/schema/001-purchase-activity.sql")) {
        into("fixtures/purchase")
    }
}
