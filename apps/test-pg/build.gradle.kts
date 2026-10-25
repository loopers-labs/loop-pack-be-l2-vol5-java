import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("test-pg.jar")
}
