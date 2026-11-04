import org.gradle.api.plugins.quality.CheckstyleExtension

sourceSets.test {
    resources.srcDir(rootProject.file("docs/week6-7/r01-order-events-inbox/fixtures"))
}

tasks.processTestResources {
    from(rootProject.file("docker/payment-lab/009-purchase-activity.sql"))
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
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:${project.properties["queryDslVersion"]}:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
    testImplementation("org.testcontainers:mysql")
    testImplementation("com.tngtech.archunit:archunit-junit5:${project.properties["archUnitVersion"]}")
}

apply(plugin = "checkstyle")

configure<CheckstyleExtension> {
    toolVersion = project.properties["checkstyleVersion"].toString()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
