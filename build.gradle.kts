plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.msfg"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.1.7"

dependencies {
    // --- Spring core ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Spring AI: model libraries, Tika document reader ---
    // Providers are wired conditionally in app code so the admin dashboard can
    // boot without cloud API keys. pgvector retrieval is implemented with SQL.
    implementation("org.springframework.ai:spring-ai-anthropic")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")

    // --- Database ---
    // Maps pgvector VECTOR columns to float[] in entities.
    // Version intentionally tracks Boot's managed Hibernate version (see below).
    implementation("org.hibernate.orm:hibernate-vector")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Domain pack loading (YAML -> records) ---
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // --- Rate limiting (public endpoint protection) ---
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // --- Token counting for chunk sizing ---
    implementation("com.knuddels:jtokkit:1.1.0")

    // --- S3 corpus sync ---
    implementation("software.amazon.awssdk:s3")

    // --- Testing ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        mavenBom("software.amazon.awssdk:bom:2.29.23")
    }
    dependencies {
        // hibernate-vector is not in Boot's BOM; pin it to Boot's Hibernate version.
        dependency("org.hibernate.orm:hibernate-vector:${importedProperties["hibernate.version"]}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
