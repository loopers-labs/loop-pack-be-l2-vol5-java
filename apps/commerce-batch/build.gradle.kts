import org.gradle.api.plugins.quality.CheckstyleExtension

tasks.processTestResources {
    from(rootProject.file("docker/payment-lab/014-weekly-ranking-snapshots.sql")) {
        into("preservation")
    }
    from(rootProject.file("docker/payment-lab/015-weekly-ranking-scores.sql")) {
        into("preservation")
    }
    from(rootProject.file("docker/payment-lab/016-weekly-ranking-publication.sql")) {
        into("preservation")
    }
    from(rootProject.file("docker/week9/001-batch-metadata-mysql.sql")) {
        into("preservation")
    }
}

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

    // querydsl
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:${project.properties["queryDslVersion"]}:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

apply(plugin = "checkstyle")

configure<CheckstyleExtension> {
    toolVersion = project.properties["checkstyleVersion"].toString()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
