# G2 Even Hub microphone audio

Both native SDKs use the production LC3 codec shipped in `flutter_ezw_lc3` at
`c8d02472bca0d5c0e55b4c31a3d8d949c375d9ca`. The original archive, headers
and Android libraries are under `third_party/flutter_ezw_lc3`. The shipped
Apache-2.0 codec notice is in `native/licenses/liblc3_LICENSE`.

## Platform builds

Android stages each of the four original ABI libraries under the distinct
SONAME `libeg3.so`. Gradle replaces one null-terminated name of equal length.
The packager checks the entire resulting library against the source with that
single substitution. Both 64-bit libraries have 16 KB PT_LOAD alignment.
A small JNI wrapper exposes the same per-stream C decoder as the Apple SDK.

iOS links the original arm64 device and x86_64 simulator objects. For arm64
simulators and macOS tests, the Apple compiler builds target-specific objects
from LLVM bitcode embedded in the corresponding original object files.
`tool/prepare_apple_lc3.py` reproduces the codec XCFramework. Its `--verify`
option checks the input archive and output against `native/ios/Codec/PROVENANCE.json`.

## PCM behavior

Each accepted 40-byte frame represents 10 ms and produces 160 signed mono
samples at 16 kHz. The C wrapper emits PCM16LE and preserves the decoder's
packet-loss concealment output. Each microphone lease owns separate decoder,
gain and speaker state for each physical endpoint.

The default gain processing follows the Even Hub host used with
`even_hub@3574ac9`. Its tagged-frame speaker smoother was compared with the
App's pinned `flutter_ezw_audio@f7402a0b` binary on the iOS simulator. Role
thresholds, normalization, first-block behavior and the one-block PCM delay
are covered by Kotlin and Swift tests. Untagged audio carries the unknown role.

## Differential checks

Run `tool/test_native_lc3.sh` on macOS to exercise invalid lengths, predictor
reset and the original five-frame golden. Apple Silicon also checks 256
continuous frames exactly against the arm64 production decoder. The long
fixture consists of 256 records of 40 encoded bytes and 320 PCM16LE bytes each.
Inputs cover silence, dual sine tones and deterministic noise. All inputs are
synthetic. The same records were checked on the arm64 iOS simulator.

`native/tests/android_lc3_binary_parity_test.c` compares the original Android
encoder/decoder with the packaged JNI decoder using the same 256-frame signal
sequence. It passed on PHB110 with all 40,960 samples equal. The Apple arm64
comparison likewise found zero differences in 40,960 samples. These checks
exercise the codec independently of the physical glasses microphone.
