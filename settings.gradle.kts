rootProject.name = "soml-kotlin"

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		google()
	}
}

pluginManagement {
	repositories {
		mavenCentral()
		google()
		gradlePluginPortal()
	}
}

// Uploads the publications to Maven Central with the Central Portal API, which has no official Gradle plugin. The credentials are only needed by the release workflow: a local build leaves them empty and never uploads.
plugins {
	id("com.gradleup.nmcp.settings") version "1.6.2"
}

nmcpSettings {
	centralPortal {
		username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME").orNull.orEmpty()
		password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD").orNull.orEmpty()
		publishingType = "AUTOMATIC"
		// Waits until Maven Central confirms the release, so that the workflow does not tag a version that failed to publish.
		publishingTimeout = java.time.Duration.ofMinutes(15)
	}
}

include("benchmark")
