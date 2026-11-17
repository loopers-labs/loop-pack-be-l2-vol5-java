dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    testImplementation("org.springframework.batch:spring-batch-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

// 주간 랭킹 원본 product_purchase_activity의 정의는 streamer 엔티티 한 벌뿐이다(D153). batch 테스트에서만 그 엔티티 클래스
// 하나를 classpath에 빌려 와 JPA(ddl-auto)가 테이블을 만들게 한다. streamer 출력 전체를 넣으면 listener·handler·Kafka 설정이
// batch 컨텍스트에 섞이므로 그 클래스 파일만 따로 복사한다. 운영 classpath에는 들어가지 않는다.
evaluationDependsOn(":apps:commerce-streamer")
val streamerMainOutput = project(":apps:commerce-streamer").extensions.getByType<SourceSetContainer>()["main"].output
val purchaseActivityEntityClasses = tasks.register<Sync>("purchaseActivityEntityClasses") {
    from(streamerMainOutput.classesDirs) {
        include("com/loopers/infrastructure/purchase/ProductPurchaseActivityJpaEntity.class")
    }
    into(layout.buildDirectory.dir("purchase-activity-entity"))
    dependsOn(":apps:commerce-streamer:classes")
}

dependencies {
    testRuntimeOnly(files(purchaseActivityEntityClasses))
}
