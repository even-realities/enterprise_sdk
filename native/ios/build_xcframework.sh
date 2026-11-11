#!/usr/bin/env bash
set -euo pipefail

# Build the Flutter-free Swift sources for the three supported iOS slices.
# The output path may be overridden for verification without touching release artifacts.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output="${1:-${script_dir}/Frameworks/EvenDevicesSDK.xcframework}"
python3 "$script_dir/../../tool/prepare_apple_lc3.py" --verify
# Validate metadata and the destructive output boundary before starting a build.
sdk_bundle_version="${SDK_BUNDLE_VERSION:-0.0.1}"
sdk_build_number="${SDK_BUILD_NUMBER:-1}"
if [[ $# -gt 1 || ! "$output" =~ \.xcframework$ || -L "$output" ]]; then
  printf 'Usage: %s [output.xcframework] (output must not be a symlink)\n' "$0" >&2
  exit 2
fi
if [[ ! "$sdk_bundle_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ || ! "$sdk_build_number" =~ ^[1-9][0-9]*$ ]]; then
  printf 'SDK_BUNDLE_VERSION must be numeric x.y.z; SDK_BUILD_NUMBER must be a positive integer.\n' >&2
  exit 2
fi
scratch="$(mktemp -d "${TMPDIR:-/private/tmp}/even-devices-ios.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/module-cache"

build_framework() {
  local target="$1" sdk="$2" module_suffix="$3" destination="$4" platform="$5"
  local framework="$destination/EvenDevicesSDK.framework"
  local module_dir="$framework/Modules/EvenDevicesSDK.swiftmodule"
  mkdir -p "$module_dir"
  # Compile the narrow wrapper and link the verified production-codec platform slice.
  local codec_objects="$destination/codec-objects"
  mkdir -p "$codec_objects"
  local codec_archive="$script_dir/Codec/EvenLC3Core.xcframework/ios-arm64/device.a"
  if [[ "$sdk" == iphonesimulator ]]; then
    codec_archive="$script_dir/Codec/EvenLC3Core.xcframework/ios-arm64_x86_64-simulator/simulator.a"
  fi
  local source
  for source in "$script_dir"/Sources/EvenLC3/even_lc3.c; do
    xcrun --sdk "$sdk" clang -target "$target" -isysroot "$(xcrun --sdk "$sdk" --show-sdk-path)" \
      -O2 -fPIC -I "$script_dir/../../third_party/flutter_ezw_lc3/lib/lc3_include" \
      -I "$script_dir/Sources/EvenLC3/include" -c "$source" \
      -o "$codec_objects/$(basename "${source%.c}").o"
  done
  xcrun --sdk "$sdk" swiftc -swift-version 5 -target "$target" \
    -sdk "$(xcrun --sdk "$sdk" --show-sdk-path)" \
    -module-cache-path "$scratch/module-cache" \
    -I "$script_dir/Sources/EvenLC3/include" \
    -parse-as-library -emit-library -emit-module -module-name EvenDevicesSDK \
    -enable-library-evolution \
    -emit-module-path "$module_dir/$module_suffix.swiftmodule" \
    -emit-module-interface-path "$module_dir/$module_suffix.swiftinterface" \
    -Xlinker -install_name -Xlinker @rpath/EvenDevicesSDK.framework/EvenDevicesSDK \
    -o "$framework/EvenDevicesSDK" \
    "$script_dir"/Sources/EvenDevicesSDK/*.swift "$codec_objects"/*.o "$codec_archive"
  cat > "$framework/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleDevelopmentRegion</key><string>en</string>
<key>CFBundleExecutable</key><string>EvenDevicesSDK</string>
<key>CFBundleIdentifier</key><string>com.evenrealities.EvenDevicesSDK</string>
<key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
<key>CFBundleName</key><string>EvenDevicesSDK</string>
<key>CFBundlePackageType</key><string>FMWK</string>
<key>CFBundleShortVersionString</key><string>$sdk_bundle_version</string>
<key>CFBundleVersion</key><string>$sdk_build_number</string>
<key>CFBundleSupportedPlatforms</key><array><string>$platform</string></array>
<key>MinimumOSVersion</key><string>15.0</string>
</dict></plist>
PLIST
}

build_framework arm64-apple-ios15.0 iphoneos arm64-apple-ios "$scratch/device" iPhoneOS
build_framework arm64-apple-ios15.0-simulator iphonesimulator arm64-apple-ios-simulator "$scratch/sim-arm64" iPhoneSimulator
build_framework x86_64-apple-ios15.0-simulator iphonesimulator x86_64-apple-ios-simulator "$scratch/sim-x86_64" iPhoneSimulator

mkdir -p "$scratch/simulator/EvenDevicesSDK.framework/Modules/EvenDevicesSDK.swiftmodule"
cp "$scratch/sim-arm64/EvenDevicesSDK.framework/Info.plist" "$scratch/simulator/EvenDevicesSDK.framework/"
cp "$scratch"/sim-*/EvenDevicesSDK.framework/Modules/EvenDevicesSDK.swiftmodule/* \
  "$scratch/simulator/EvenDevicesSDK.framework/Modules/EvenDevicesSDK.swiftmodule/"
xcrun lipo -create \
  "$scratch/sim-arm64/EvenDevicesSDK.framework/EvenDevicesSDK" \
  "$scratch/sim-x86_64/EvenDevicesSDK.framework/EvenDevicesSDK" \
  -output "$scratch/simulator/EvenDevicesSDK.framework/EvenDevicesSDK"

mkdir -p "$(dirname "$output")"
rm -rf "$output"
xcodebuild -create-xcframework \
  -framework "$scratch/device/EvenDevicesSDK.framework" \
  -framework "$scratch/simulator/EvenDevicesSDK.framework" \
  -output "$output"
