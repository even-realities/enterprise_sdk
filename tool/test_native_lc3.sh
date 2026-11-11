#!/usr/bin/env bash
set -euo pipefail
# Run original-binary PCM compatibility without Flutter or mobile hardware.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/even-lc3-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT
python3 "$root/tool/prepare_apple_lc3.py" --verify
"${CC:-clang}" -O2 -I "$root/third_party/flutter_ezw_lc3/lib/lc3_include" \
  -I "$root/native/ios/Sources/EvenLC3/include" \
  "$root/native/tests/lc3_decoder_test.c" "$root/native/ios/Sources/EvenLC3/even_lc3.c" \
  "$root/native/ios/Codec/EvenLC3Core.xcframework/macos-arm64_x86_64/macos.a" -o "$work/lc3-test"
"$work/lc3-test" "$root/native/tests/fixtures/g2_audio_40.lc3" \
  "$root/native/tests/fixtures/g2_audio_5frames_s16le.pcm" \
  "$root/native/tests/fixtures/g2_audio_apple_arm64_continuous.bin"
