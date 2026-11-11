# Publish the native Android and iOS SDKs

This repository owns native source, shared protocol tests, native examples and
release tooling. Flutter consumers live in the separate
[Flutter enterprise SDK repository](https://github.com/even-realities/flutter_enterprise_sdk).
Neither platform SDK requires Flutter to build or run.

## Release identity and compatibility

| Platform | Distribution |
| --- | --- |
| Android | Maven Central: `com.evenrealities.devices:even-devices-native-android` |
| iOS | SPM: `https://github.com/even-realities/enterprise_sdk.git`, product `EvenDevicesSDK` |
| License | Apache 2.0, with retained third-party notices |

The migration imports **0.1.0-beta.14** as an existing release, with its original
XCFramework ZIP unchanged. Android beta.14 is already in Maven Central. Do not
upload, replace, rebuild-and-relabel, or delete that published coordinate. The
Central signer/uploader explicitly refuses beta.14. Local unsigned inspection
remains available. The older Flutter repository's tags, manifests and artifacts
remain intact so existing clients continue to resolve their pinned versions.

The examples below use `0.1.0-beta.15` **only as a future-version example**; they
do not imply that it is published. Choose an unused version and update `VERSION`
in the same reviewed source commit. Kotlin and Swift should be released and
accepted together when their shared contracts change.

## 1. Prepare a clean, reviewable source commit

Use the repository's pinned Android wrapper/toolchain and Xcode for iOS; no
Flutter installation is needed. Android requires a configured Android SDK/NDK
and compatible JDK. iOS device/simulator XCFramework packaging requires macOS and
Xcode. See each platform example's README for consumer build commands.

Run native tests and codec preparation/verification, update the API guides and
version, and commit the build inputs before packaging. The platform packagers
reject uncommitted source changes and record `source_commit`/`source_tree`.
They never publish or create tags. Keep generated files under ignored build
paths; do not add licenses, portal/GPG credentials or device logs to Git.

```bash
# After reviewing and committing the selected release version and build inputs:
tool/package_native_sdk.sh 0.1.0-beta.15
```

To work on one platform only:

```bash
tool/package_native_sdk.sh 0.1.0-beta.15 --platform android
tool/package_native_sdk.sh 0.1.0-beta.15 --platform ios
```

Default output is `build/distribution/<version>/{android,ios}`. Each destination
must be new; use `--output-dir` on the combined script for a separate rebuild.
Do not clean an existing signed release to make room for another build.

## 2. Prepare the Android Maven Central bundle

The Android platform package contains `maven/`, legal notices,
`PROVENANCE.json`, and `SHA256SUMS`. Pass **that directory** to the Central
packager; it does not read a Flutter pubspec or a Flutter package's bundled AAR:

```bash
python3 tool/package_maven_central.py \
  --version 0.1.0-beta.15 \
  --distribution build/distribution/0.1.0-beta.15/android \
  --unsigned
```

The default version comes from `VERSION`; the default input path is
`build/distribution/<version>/android`. Default output is
`build/central/<version>/unsigned`. An explicit `--output-dir` must name a new
path. Inputs and output cannot overlap.

The packager verifies distribution SHA-256 inventory, Maven coordinates,
Gradle metadata and AAR hashes, absence of Flutter runtime classes, notices,
and exact clean source commit/tree. It builds the source classifier from
**tracked source at the native build revision** in this repository, not from
whatever happens to be in the working directory. Check out that commit if the
current HEAD differs. It preserves AAR bytes/dependencies and creates:

- Public POM metadata pointing to `even-realities/enterprise_sdk`.
- `-sources.jar` with Android Kotlin/C/JNI sources and legal notices.
- `-javadoc.jar` containing the native Android integration guide.
- `.md5`, `.sha1`, `.sha256` and `.sha512` for each primary file.
- A deterministic Central ZIP and `release.json` identifying its byte hashes.

Unsigned output is explicitly **not upload-ready**; the uploader rejects it.

### Sign with the company's existing GPG identity

The GPG release key is separate from the glasses' enterprise signing system.
Keep its private material outside Git. Use the company-owned full fingerprint;
unlock it through GPG agent rather than putting a passphrase in arguments.
Ensure its public key is retrievable following the
[Sonatype GPG requirements](https://central.sonatype.org/publish/requirements/gpg/).

```bash
python3 tool/package_maven_central.py \
  --version 0.1.0-beta.15 \
  --distribution build/distribution/0.1.0-beta.15/android \
  --signing-key <FULL_COMPANY_GPG_FINGERPRINT>
```

The default output is `build/central/<version>/signed`. Each primary artifact is
signed and its signature is verified against the requested fingerprint. Failure
leaves no completed output directory. `upload_ready: true` establishes only
local checks; it does not establish Central namespace access, public-key
availability or publication.

### Upload, validate, then publish

The company Central Portal account must own the verified `com.evenrealities`
namespace. Inject its **Portal user token pair**, not a website password, into
the process/CI secret environment:

```text
CENTRAL_TOKEN_USERNAME=<Portal token username>
CENTRAL_TOKEN_PASSWORD=<Portal token password>
```

Do not commit credentials, send them in chat, or put them in command arguments.
The publisher uses only the fixed Sonatype endpoint, refuses redirects and
redacts HTTP failure bodies. It does not retry a potentially accepted upload.

```bash
python3 tool/publish_maven_central.py upload \
  build/central/0.1.0-beta.15/signed
python3 tool/publish_maven_central.py status <deployment_id>
# After server state is VALIDATED and the release has been approved:
python3 tool/publish_maven_central.py publish <deployment_id>
python3 tool/publish_maven_central.py status <deployment_id>
```

`upload` defaults to `USER_MANAGED`. Add `--publish` only when automatic
publication after server validation is intended. `UPLOADED`, `VALIDATED` and
`PUBLICATION_REQUESTED` are intermediate states; only `PUBLISHED` means Central
has completed publication. Then download the public coordinate and compare its
AAR hash with the release record before updating consumers.

See the [Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/)
and [Central publishing requirements](https://central.sonatype.org/publish/requirements/).

## 3. Publish the iOS SPM version

SPM consumes this repository's root `Package.swift` and immutable SemVer tag.
The source package is `native/ios/Package.swift`; the root manifest is the
**binary distribution** entry. Product name remains `EvenDevicesSDK`.

The iOS packager runs native tests, builds the XCFramework, verifies consumer
links for arm64 devices and both simulator architectures, embeds notices and
creates the final ZIP, checksum and provenance. Inspect that output before
copying its immutable ZIP into `spm/` and updating only the root manifest's
versioned path. Do not overwrite old ZIPs or tags.

```bash
cp build/distribution/0.1.0-beta.15/ios/EvenDevicesSDK-0.1.0-beta.15.xcframework.zip spm/
swift package compute-checksum spm/EvenDevicesSDK-0.1.0-beta.15.xcframework.zip
# Edit Package.swift to the new versioned ZIP; review provenance/checksums/docs.
swift package dump-package
```

The normal root manifest uses a repository-relative binary ZIP, downloaded with
the tagged Git package. If a future release uses a fixed HTTPS release-asset URL,
`SPM_BINARY_URL` can generate `Package.remote.swift` with the final ZIP checksum;
never use a temporary URL, credentials, query parameters or a checksum computed
before the final archive is complete.

Commit the reviewed manifest/artifact/provenance changes with the repository's
Lore-style decision trailers. After publishing that commit, create and push the
new tag exactly once:

```bash
git tag -a 0.1.0-beta.15 -m 'Release verified enterprise native SDK 0.1.0-beta.15'
git push origin main refs/tags/0.1.0-beta.15
```

Use a fresh consumer checkout/cache to resolve the exact remote tag and build a
real iOS application against `EvenDevicesSDK`. A manifest parse alone does not
verify an application link. For the initial migration, the new beta.14 tag must
resolve the byte-identical original framework, and the old Flutter-hosted tag
must remain available.

## 4. Consumer update and acceptance

Update Flutter's iOS package URL/identity to this repository and pin the same
native version as its Android Maven dependency. Run both native example apps and
the Flutter example's platform builds. For each new behavioral release, record
actual OS, device and firmware versions while testing exact-SN denial, dual-leg
READY, reconnect reauthorization, Even Hub renewals/clicks, microphone PCM,
time sync and OTA cancellation/readback. Build success, transfer progress or a
server publication state does not replace hardware acceptance.

The migration itself publishes repository structure and preserves beta.14
runtime bytes; it does not assert new physical-device acceptance.
