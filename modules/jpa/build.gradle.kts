import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `java-library`
    `java-test-fixtures`
}

tasks.named<ProcessResources>("processTestFixturesResources") {
    from(rootProject.file("docker/payment-lab")) {
        include("000-*.sql", "003-*.sql", "004-*.sql", "005-*.sql", "006-*.sql", "007-*.sql",
            "008-*.sql", "009-*.sql", "010-*.sql", "011-*.sql", "012-*.sql", "013-*.sql")
        into("preservation/commerce")
    }
}

dependencies {
    // jpa
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    // querydsl
    api("io.github.openfeign.querydsl:querydsl-jpa:${project.properties["queryDslVersion"]}")
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:${project.properties["queryDslVersion"]}:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    // jdbc-mysql
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.testcontainers:mysql")

    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testFixturesImplementation("org.testcontainers:mysql")
}
