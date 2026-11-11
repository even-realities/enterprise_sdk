# Native SDK provenance

This repository imports the native source snapshot used by the already published **0.1.0-beta.14** distribution. The original clean source commit is `f931d52d4716fd150f885f5168b64faabf20783a`, source tree `dad18a95a6d532bf8975e52cbf3f39320470ae00`.

- `source/native-sdk-0.1.0-beta.14.tar.gz` preserves the original self-contained Git bundle, its upstream projection/history, and checkout instructions. It is retained for the historical build records' source reference.
- `native/` and `third_party/` were imported from that snapshot. Repository/build metadata and examples are managed here; repository migration does not change runtime protocol code.
- `build_records/android.json`, `build_records/ios.json`, and `spm/provenance.json` are **original release records**, not claims that migration rebuilt those exact binaries. Their old source identities are intentionally preserved.
- `release.json` records the original Maven coordinate, original AAR checksum and original XCFramework ZIP checksum. Maven Central continues hosting the original AAR; the existing version must not be overwritten or republished.
- `checksums.sha256` covers the imported release records, source archive and binary ZIP. `tool/verify_repository.py` checks them independently of current source builds.

The first SPM tag in `even-realities/enterprise_sdk` uses the original ZIP (`c32984425afffda456fccccb738b0c69d29080bdbe07d503154c501eac260e78`). Existing tags/assets under `flutter_enterprise_sdk` remain immutable and available. Future releases must build from a clean commit here, record that source commit, refresh checksums, and publish a new immutable version.

BLE, utility and codec upstream revisions, retained licenses and transformations are described in [upstream sources](native/UPSTREAM_SOURCES.md), [codec inputs](native/codec/README.md), [codec provenance](native/ios/Codec/PROVENANCE.json), and [third-party notices](THIRD_PARTY_NOTICES.md). `UPSTREAM_FILES.tsv` records blob identities from the original imported history; those historic upstream commits can be inspected in the preserved source bundle, not assumed to be ancestors of this repository.

No signing private key, portal credentials, real device license or personal device log is included. Build checks demonstrate reproducibility and linkage, not new physical-device acceptance.
