plugins {
    id("org.jsonschema2pojo") version "1.3.3"
}

jsonSchema2Pojo {
    setSource(files(rootProject.file("event-contracts/schemas/order/order-confirmed.v1.schema.json")))
    targetDirectory = layout.buildDirectory.dir("generated/sources/event-contracts/main").get().asFile
    targetPackage = "com.loopers.purchase.infrastructure.kafka.contract.orderconfirmed"
    setSourceType("jsonschema")
    useTitleAsClassname = true
    includeAdditionalProperties = false
    useLongIntegers = true
    dateTimeType = "java.lang.String"
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
sourceSets.main {
    resources.srcDir(rootProject.file("event-contracts"))
}
