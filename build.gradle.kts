import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.android.multiplatform)
	// The benchmark's plugin, loaded once here so that Gradle can check its version.
	alias(libs.plugins.kotlin.jvm) apply false
	`maven-publish`
	signing
}

group = "sh.soml"
version = "0.0.0"

// The absolute path of the test resources, such as the conformance suite, for the tests on every platform, because the tests on Node.js do not run in the project directory.
val generateTestPaths by tasks.registering {
	val directory = layout.buildDirectory.dir("generated/testPaths")
	val resources = layout.projectDirectory.dir("src/commonTest/resources").asFile.absolutePath.replace("\\", "/")
	inputs.property("resources", resources)
	outputs.dir(directory)

	doLast {
		val file = directory.get().file("sh/soml/kotlin/TestPaths.kt").asFile
		file.parentFile.mkdirs()
		val escapedResources = resources.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\r", "\\r").replace("\n", "\\n")
		file.writeText("package sh.soml.kotlin\n\ninternal const val RESOURCES_DIRECTORY = \"$escapedResources\"\n\ninternal const val CONFORMANCE_DIRECTORY = \"$escapedResources/conformance\"\n")
	}
}

kotlin {
	explicitApi()

	compilerOptions {
		allWarningsAsErrors = true
		// `expect` and `actual` classes are in beta, and this library uses them only internally.
		freeCompilerArgs.add("-Xexpect-actual-classes")
	}

	jvm {
		compilerOptions {
			// Java 21 bytecode and API, with any JDK from 21 on, so that no toolchain needs to be installed. Java 21 is the first with every API the library needs: the shortest `Double.toString()` of JDK 19 and the bounded `String.indexOf()` of JDK 21.
			jvmTarget = JvmTarget.JVM_21
			freeCompilerArgs.add("-Xjdk-release=21")
		}
	}

	android {
		namespace = "sh.soml.kotlin"
		compileSdk = 36
		minSdk = 33

		compilerOptions {
			jvmTarget = JvmTarget.JVM_1_8
		}

		withHostTest {
		}
		withDeviceTest {
			instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		}
	}

	// The tests read the whole conformance suite, which takes longer than Mocha's default limit of 2 seconds per test.
	js {
		nodejs {
			testTask {
				useMocha {
					timeout = "120s"
				}
			}
		}
	}

	@OptIn(ExperimentalWasmDsl::class)
	wasmJs {
		nodejs {
			testTask {
				useMocha {
					timeout = "120s"
				}
			}
		}
	}

	macosArm64()
	iosArm64()
	iosSimulatorArm64()
	linuxX64()
	linuxArm64()
	mingwX64()

	// The platforms other than the JVM share what they lack from the JDK.
	@OptIn(ExperimentalKotlinGradlePluginApi::class)
	applyDefaultHierarchyTemplate {
		common {
			group("nonJvm") {
				withJs()
				withWasmJs()
				withNative()
			}
		}
	}

	sourceSets {
		// These Java APIs are available on both the desktop JVM and Android API 33. Share their physical sources without commonizing java.* into multiplatform metadata.
		jvmMain {
			kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
		}

		androidMain {
			kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
		}

		getByName("androidDeviceTest") {
			resources.srcDir("src/commonTest/resources")

			dependencies {
				implementation(kotlin("test"))
				implementation(libs.androidx.test.runner)
			}
		}

		commonMain.dependencies {
			api(libs.kotlinx.serialization.core)
		}

		commonTest {
			kotlin.srcDir(generateTestPaths)

			dependencies {
				implementation(kotlin("test"))
				implementation(libs.kotlinx.serialization.json)
				// To read the conformance suite on every platform.
				implementation(libs.kotlinx.io)
			}
		}

		jvmTest.dependencies {
			// The Java implementation, as a second opinion for the differential test.
			implementation(libs.soml.java)
		}
	}
}

// No Java sources, but Gradle checks that the Java target matches Kotlin's.
tasks.withType<JavaCompile>().matching { it.name.startsWith("compileJvm") }.configureEach {
	options.release = 21
}

tasks.named<Test>("jvmTest") {
	useJUnitPlatform()

	// `soml-java` is built for Java 25, so the differential test runs only on a JDK that can load it. The other tests run on the minimum Java version too.
	if (JavaVersion.current() < JavaVersion.VERSION_25) {
		exclude("**/DifferentialTest.class")
	}
}

publishing {
	publications.withType<MavenPublication>().configureEach {
		// Maven Central requires a javadoc jar next to every artifact. Kotlin has no Javadoc, so it is empty, and the API is documented in the sources jar. Each publication gets its own, named for its coordinates, because a shared jar would be signed once per publication.
		val publicationArtifactId = artifactId

		artifact(tasks.register<Jar>("${name}JavadocJar") {
			archiveBaseName.set(publicationArtifactId)
			archiveVersion.set(project.version.toString())
			archiveClassifier.set("javadoc")
		})

		pom {
			name = "SOML for Kotlin"
			description = "Read and write SOML, a config format for humans, in Kotlin"
			url = "https://github.com/soml-lang/soml-kotlin"

			licenses {
				license {
					name = "MIT"
					url = "https://opensource.org/licenses/MIT"
				}
			}

			developers {
				developer {
					name = "Sindre Sorhus"
					email = "sindresorhus@gmail.com"
					url = "https://sindresorhus.com"
				}
			}

			scm {
				url = "https://github.com/soml-lang/soml-kotlin"
				connection = "scm:git:https://github.com/soml-lang/soml-kotlin.git"
				developerConnection = "scm:git:git@github.com:soml-lang/soml-kotlin.git"
			}
		}
	}
}

// Maven Central needs a signature for every file it publishes. The release workflow supplies the key, and a local build has none, so it makes no signatures.
signing {
	val key = providers.environmentVariable("MAVEN_GPG_KEY").orNull

	if (key != null) {
		useInMemoryPgpKeys(key, providers.environmentVariable("MAVEN_GPG_PASSPHRASE").orNull)
		sign(publishing.publications)
	}
}
