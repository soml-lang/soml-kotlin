import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
	application
}

kotlin {
	compilerOptions {
		// Java 21 bytecode and API, with any JDK from 21 on, so that no toolchain needs to be installed.
		jvmTarget = JvmTarget.JVM_21
		freeCompilerArgs.add("-Xjdk-release=21")
		allWarningsAsErrors = true
	}
}

// No Java sources, but Gradle checks that the Java target matches Kotlin's.
tasks.withType<JavaCompile>().configureEach {
	options.release = 21
}

dependencies {
	implementation(project(":"))
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.jackson.databind)
	implementation(libs.jackson.toml)
	implementation(libs.tomlj)
}

application {
	mainClass = "sh.soml.kotlin.benchmark.CpuTimeKt"
	applicationDefaultJvmArgs = listOf("-Xms1g", "-Xmx1g")
}

tasks.named<JavaExec>("run") {
	workingDir = projectDir
}
