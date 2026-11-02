plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.test {
    systemProperty("week6.revision", project.version.toString())
    val workingTree = providers.exec {
        workingDir(rootProject.projectDir)
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText.get()
    systemProperty("week6.working-tree-status", if (workingTree.isBlank()) "clean" else "uncommitted")
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
    testImplementation(project(path = ":apps:commerce-api", configuration = "week6IntegrationSupport"))
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}
