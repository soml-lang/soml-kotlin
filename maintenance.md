# Maintaining

## Test

Use JDK 21 or later and the Gradle wrapper. Dependency versions are in `gradle/libs.versions.toml`. Android builds need SDK platform 36 and build tools 36.0.0; set `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`.

```sh
./gradlew jvmTest :benchmark:compileKotlin
./gradlew testAndroidHostTest assembleAndroidMain assembleAndroidDeviceTest
./gradlew jsNodeTest wasmJsNodeTest
```

On an Apple silicon Mac, also run:

```sh
./gradlew macosArm64Test
```

CI tests Java 21 and 27, JS, Wasm, macOS arm64, Linux x64, and Windows x64, and it runs the Android host tests and builds the Android artifacts. Native tests need the corresponding host, and Kotlin/Native has no Linux arm64 host, so the Linux arm64 target is built in the release but not tested. Common tests include the conformance suite, float vectors and seeded property tests. `jvmTest` also compares mutated conformance cases with the published `soml-java` dependency; no sibling Java checkout is needed for that test. That dependency is built for Java 25, so the comparison runs only on Java 25 and later.

### Android devices

CI runs the Android host tests on the desktop JVM and builds the device test APK, but it does not boot an emulator. To check ART and the minimum Android API before a release, boot an API 33 emulator or connect a device, then run:

```sh
./gradlew connectedAndroidDeviceTest
```

Repeat on API 36, the newest supported version. The device tests check canonical floats, midpoint rounding, editing, strict UTF-8, Java time and streams. Assembling the test APK does not run them. The minimum supported version is Android 13/API 33; core-library desugaring is not needed.

## Update the conformance suite

`src/commonTest/resources/conformance` is a copy of the suite in the `soml` repository. Do not edit it here. Check out `soml` next to this repository, then run:

```sh
../soml/sync-conformance.sh src/commonTest/resources/conformance
./gradlew jvmTest
```

The sync script records the source commit in the copied suite. Run the other platform tests after updating it too.

## Regenerate float vectors

The fixtures in `src/commonTest/resources/floats` come from the seeded Java generators in `scripts`. `FloatFixture.java` uses the independent Java implementation for canonical spelling; `HalfwayFixture.java` uses exact `BigDecimal` arithmetic for halfway values and their neighbors. Do not generate expected results with the Kotlin implementation being tested.

This step needs JDK 25 or later for the current `soml-java` checkout, for both Maven and `java`. With `soml-java` checked out next to this repository and compiled with `mvn -f ../soml-java/pom.xml compile`, run:

```sh
java --class-path ../soml-java/target/classes scripts/FloatFixture.java > src/commonTest/resources/floats/canonical.txt
java scripts/HalfwayFixture.java > src/commonTest/resources/floats/halfway.txt
./gradlew jvmTest
```

Review the fixture diff and run the other platform tests, including Android device tests, after regenerating.

## Benchmark

```sh
./gradlew :benchmark:run
```

The harness reports thread CPU time per document, with warmup and a median of 15 rotating rounds. Run the complete benchmark three times on a quiet machine and use the median of each cell for the [README table](readme.md#performance). Record the machine, JDK and library versions. CPU time reduces interference from other work, but host load can still affect results.

To measure one operation or library:

```sh
./gradlew :benchmark:run --args="read --library=SOML"
```

`benchmark/fixtures` is a copy of the shared fixtures in `soml`. Do not edit the copies by hand. To update them:

```sh
rsync --archive --delete ../soml/benchmark/ benchmark/fixtures/
```

Fixtures belong only to the benchmark module and must stay out of published library artifacts.

## Publish a new version

The [Release workflow](.github/workflows/release.yml) publishes to Maven Central. Maven Central has no official Gradle plugin, so the build uses [NMCP](https://github.com/GradleUp/nmcp), which uploads the existing `maven-publish` publications to the Central Portal. Maven Central has no trusted publishing, so the workflow uses a token and a signing key from the repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_GPG_KEY` (an ASCII-armored secret key), and `MAVEN_GPG_PASSPHRASE`.

For each version:

1. Run `sh scripts/set-version.sh 1.2.3`. It sets the version in `build.gradle.kts` and in the install snippet of the readme, and commits.
2. Review the commit with `git show`, then push: `git push`
3. Run the Release workflow on `main` from the Actions tab, or with `gh workflow run release.yml`. It tests, signs, uploads, [publishes automatically](https://central.sonatype.org/publish/publish-portal-maven/#automatic-publishing), waits until Maven Central confirms publication, and pushes the `v1.2.3` tag.
4. Create a release on GitHub.

The workflow runs on macOS, because the Apple targets only build there, and every target publishes from that one host. If the workflow fails or times out, check the [deployment](https://central.sonatype.com/publishing/deployments) before you run it again. A published version cannot be overwritten.

Publication metadata is in the `publishing` block of `build.gradle.kts`. Each publication gets its own empty javadoc jar, because Maven Central requires one and Kotlin has no Javadoc; the API is documented in the sources jar. Signing only happens when `MAVEN_GPG_KEY` is set, so a local build needs no key.
