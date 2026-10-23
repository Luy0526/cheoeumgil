import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 헤맴 지도 집계 서버: receives anonymous struggle events, serves hotspots. JDK HttpServer, no frameworks.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.cheoeumgil.server.MainKt")
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
