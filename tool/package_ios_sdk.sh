#!/usr/bin/env bash
set -euo pipefail

# Build a self-contained binary Swift package locally. Nothing in this script
# uploads artifacts or changes the frameworks embedded by the Flutter plugin.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk_root="$(cd "$script_dir/.." && pwd)"
if [[ $# -eq 1 && "$1" == '--help' ]]; then
  cat <<'HELP'
Usage: tool/package_ios_sdk.sh VERSION [OUTPUT_DIR]

Build and validate a local binary SPM package; never upload or push.
Default output: build/distribution/VERSION/ios (must not already exist).
SDK_BUNDLE_VERSION: numeric x.y.z; defaults to VERSION without prerelease.
SDK_BUILD_NUMBER: positive integer; defaults to 1.
SPM_BINARY_URL: optional fixed HTTPS ZIP URL for Package.remote.swift.
HELP
  exit 0
fi
if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf 'Usage: %s VERSION [OUTPUT_DIR]\n' "$0" >&2
  exit 2
fi
sdk_release="$1"
output="${2:-$sdk_root/build/distribution/$sdk_release/ios}"

# Resolve the path and validate all externally supplied metadata before creating
# directories. Existing output is deliberately immutable, including symlinks.
validated="$(python3 - "$sdk_root" "$sdk_release" "$output" <<'PY'
from pathlib import Path
import os, re, sys
root, version, output = sys.argv[1:]
sys.path.insert(0, str(Path(root) / 'tool'))
from spm_manifest import validate_binary_url
match = re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?', version)
if not match or '+' in version or any(p.isdigit() and len(p) > 1 and p.startswith('0') for p in (match.group(4) or '').split('.')):
    sys.exit('VERSION must be a valid SemVer, for example 0.1.0-beta.1.')
bundle = os.environ.get('SDK_BUNDLE_VERSION', '.'.join(match.group(i) for i in (1, 2, 3)))
build = os.environ.get('SDK_BUILD_NUMBER', '1')
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', bundle) or not re.fullmatch(r'[1-9][0-9]*', build):
    sys.exit('SDK_BUNDLE_VERSION must be numeric x.y.z; SDK_BUILD_NUMBER must be positive.')
path = Path(output).expanduser()
if any(c in str(path) for c in ('\n', '\r')) or path.exists() or path.is_symlink():
    sys.exit('Output must be a new directory, without newline characters.')
path = path.resolve()
root = Path(root).resolve()
if path == root or path in root.parents or (root in path.parents and not path.is_relative_to(root / 'build')):
    sys.exit('Inside this repository, output must be below build/.')
url = os.environ.get('SPM_BINARY_URL', '')
if url:
    validate_binary_url(url)
print(path)
print(bundle)
print(build)
PY
)"
output="$(printf '%s\n' "$validated" | sed -n '1p')"
export SDK_BUNDLE_VERSION="$(printf '%s\n' "$validated" | sed -n '2p')"
export SDK_BUILD_NUMBER="$(printf '%s\n' "$validated" | sed -n '3p')"
source_commit="$(bash "$script_dir/native_release_source.sh")"
mkdir -p "$(dirname "$output")"
work="$(mktemp -d "$(dirname "$output")/.even-ios-package.XXXXXX")"
trap 'rm -rf "$work"' EXIT
stage="$work/package"
mkdir -p "$stage/Frameworks" "$stage/licenses" "$work/module-cache"

# Keep validation intermediates outside the distributable package directory.
swift test --package-path "$sdk_root/native/ios" --scratch-path "$work/swift-tests"
bash "$sdk_root/native/ios/build_xcframework.sh" "$stage/Frameworks/EvenDevicesSDK.xcframework"
cp "$sdk_root/LICENSE" "$stage/LICENSE"
cp "$sdk_root/NOTICE" "$stage/NOTICE"
cp "$sdk_root/native/licenses/flutter_ezw_ble_LICENSE" "$stage/licenses/"
cp "$sdk_root/native/licenses/liblc3_LICENSE" "$stage/licenses/"
cp "$sdk_root/native/licenses/IOS_THIRD_PARTY_NOTICES.md" "$stage/THIRD_PARTY_NOTICES.md"
# Keep notices inside the binary artifact too: remote SwiftPM downloads only
# this XCFramework ZIP, without the surrounding Git package files.
cp "$stage/LICENSE" "$stage/NOTICE" "$stage/THIRD_PARTY_NOTICES.md" "$stage/Frameworks/EvenDevicesSDK.xcframework/"
mkdir -p "$stage/Frameworks/EvenDevicesSDK.xcframework/licenses"
cp "$stage/licenses/"* "$stage/Frameworks/EvenDevicesSDK.xcframework/licenses/"
cat > "$stage/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription

// Distribute the compiled iOS module; source builds use native/ios/Package.swift.
let package = Package(
    name: "EvenDevicesSDK",
    platforms: [.iOS(.v15)],
    products: [.library(name: "EvenDevicesSDK", targets: ["EvenDevicesSDK"])],
    targets: [.binaryTarget(name: "EvenDevicesSDK", path: "Frameworks/EvenDevicesSDK.xcframework")]
)
SWIFT

# The final archive is created before its checksum. Any later re-signing or
# mutation requires a new archive and a matching manifest checksum.
zip_name="EvenDevicesSDK-$sdk_release.xcframework.zip"
ditto -c -k --sequesterRsrc --keepParent "$stage/Frameworks/EvenDevicesSDK.xcframework" "$stage/$zip_name"
swift package compute-checksum "$stage/$zip_name" > "$stage/checksum.txt"
swift package --package-path "$stage" --scratch-path "$work/manifest-build" dump-package > "$work/manifest.json"

# Link a consumer against each distributed slice rather than treating manifest
# parsing as evidence that a real iOS application can import the binary.
cat > "$work/Consumer.swift" <<'SWIFT'
import EvenDevicesSDK
print(String(describing: EvenDevicesG2SDK.self))
SWIFT
for slice in 'iphoneos arm64-apple-ios15.0 ios-arm64' \
             'iphonesimulator arm64-apple-ios15.0-simulator ios-arm64_x86_64-simulator' \
             'iphonesimulator x86_64-apple-ios15.0-simulator ios-arm64_x86_64-simulator'; do
  read -r platform target identifier <<< "$slice"
  xcrun --sdk "$platform" swiftc -target "$target" \
    -sdk "$(xcrun --sdk "$platform" --show-sdk-path)" \
    -module-cache-path "$work/module-cache" \
    -F "$stage/Frameworks/EvenDevicesSDK.xcframework/$identifier" \
    -framework EvenDevicesSDK "$work/Consumer.swift" -o "$work/consumer-$target"
done

# Metadata contains source identities and tool versions, never publishing tokens.
python3 - "$sdk_root" "$stage" "$sdk_release" "$zip_name" <<'PY'
from pathlib import Path
import hashlib, json, os, subprocess, sys, zipfile
root, stage = Path(sys.argv[1]), Path(sys.argv[2])
sys.path.insert(0, str(root / 'tool'))
from spm_manifest import render_remote_manifest
version, zip_name = sys.argv[3:]
checksum = (stage / 'checksum.txt').read_text().strip()
# Remote SwiftPM downloads only the ZIP, so check its complete legal payload.
with zipfile.ZipFile(stage / zip_name) as archive:
    for name, source in [('LICENSE', root / 'LICENSE'), ('NOTICE', root / 'NOTICE'),
                         ('THIRD_PARTY_NOTICES.md', root / 'native/licenses/IOS_THIRD_PARTY_NOTICES.md'),
                         ('licenses/flutter_ezw_ble_LICENSE', root / 'native/licenses/flutter_ezw_ble_LICENSE'),
                         ('licenses/liblc3_LICENSE', root / 'native/licenses/liblc3_LICENSE')]:
        assert archive.read('EvenDevicesSDK.xcframework/' + name) == source.read_bytes()
url = os.environ.get('SPM_BINARY_URL')
remote = render_remote_manifest((stage / 'Package.swift').read_text(), url, checksum)
(stage / ('Package.remote.swift' if url else 'Package.remote.swift.example')).write_text(remote)
inputs = [*sorted((root / 'native/ios/Sources/EvenDevicesSDK').glob('*.swift')),
          root / 'native/ios/build_xcframework.sh', root / 'tool/package_ios_sdk.sh',
          root / 'tool/spm_manifest.py', root / 'tool/native_release_source.sh', root / 'native/licenses/IOS_THIRD_PARTY_NOTICES.md',
          root / 'LICENSE', root / 'NOTICE', root / 'native/licenses/flutter_ezw_ble_LICENSE']
inputs += sorted(p for p in (root / 'native/ios/Codec').rglob('*') if p.is_file())
inputs += [root / 'tool/prepare_apple_lc3.py', root / 'third_party/flutter_ezw_lc3/ios/Classes/framework/liblc3.a']
inputs += sorted((root / 'third_party/flutter_ezw_lc3/lib/lc3_include').glob('*.h'))
inputs += [root / 'tool/test_native_lc3.sh', root / 'native/tests/lc3_decoder_test.c', *sorted((root / 'native/tests/fixtures').glob('g2_audio_*')), root / 'native/ios/Package.swift', root / 'native/ios/Sources/EvenLC3/even_lc3.c',
           *sorted((root / 'native/ios/Sources/EvenLC3/include').glob('*')), root / 'native/licenses/liblc3_LICENSE']
run = lambda *args: subprocess.check_output(args, text=True, cwd=root).strip()
metadata = {
    'version': version, 'license': 'Apache-2.0', 'bundle_version': os.environ['SDK_BUNDLE_VERSION'],
    'build_number': os.environ['SDK_BUILD_NUMBER'], 'source_commit': run('git', 'rev-parse', 'HEAD'),
    'source_tree': run('git', 'rev-parse', 'HEAD^{tree}'),
    'source_repository': os.environ.get('SDK_SOURCE_REPOSITORY', 'https://github.com/even-realities/enterprise_sdk'),
    'source_dirty': bool(run('git', 'status', '--porcelain', '--untracked-files=all')),
    'source_sha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
    'xcode': run('xcodebuild', '-version'), 'swift': run('swift', '--version'),
    'zip': zip_name, 'zip_sha256': checksum,
    'validation': ['swift tests', 'SwiftPM manifest', 'arm64 device consumer link',
                   'arm64 simulator consumer link', 'x86_64 simulator consumer link'],
}
if url:
    metadata['validation'].append('remote SwiftPM manifest')
(stage / 'provenance.json').write_text(json.dumps(metadata, indent=2) + '\n')
(stage / 'README.md').write_text(f'''# EvenDevicesSDK {version}

This directory is a local binary Swift package for iOS 15 and later. In Xcode,
choose Add Package Dependencies > Add Local and select this directory. Add the
EvenDevicesSDK product to your app target and use `import EvenDevicesSDK`.
The host supplies NSBluetoothAlwaysUsageDescription and a valid License Bundle.

For Git/SPM distribution, commit this directory with Package.swift at the
repository root, then create version tag {version}. The remote manifest is an
alternative to the local-path manifest, after configuring artifact hosting and
authentication; no remote upload or Git operation has been performed.

{zip_name} expands directly to EvenDevicesSDK.xcframework. checksum.txt is the
SwiftPM checksum for exactly that ZIP. provenance.json records the source and
local validation. See LICENSE and THIRD_PARTY_NOTICES.md before distribution.

The public native repository is https://github.com/even-realities/enterprise_sdk.
This release supports enterprise G2; R1 is outside its scope.
Local build/link checks do not replace Bluetooth hardware acceptance.
''')
with (stage / 'checksums.sha256').open('w') as output:
    for p in sorted(stage.rglob('*')):
        if p.is_file() and p.name != 'checksums.sha256':
            output.write(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(stage)}\n')
PY

# Validate the exact remote manifest too; dump-package compiles it without
# downloading the ZIP. Keep this scratch package outside the final payload.
if [[ -n "${SPM_BINARY_URL:-}" ]]; then
  mkdir -p "$work/remote-manifest"
  cp "$stage/Package.remote.swift" "$work/remote-manifest/Package.swift"
  swift package --package-path "$work/remote-manifest" dump-package > "$work/remote-manifest.json"
fi

bash "$script_dir/native_release_source.sh" "$source_commit" >/dev/null
# Rename the completed package directly. Unlike mv, this cannot nest it inside
# another completed distribution if concurrent builders target the same path.
python3 - "$stage" "$output" <<'PY'
from pathlib import Path
import os, sys
source, target = map(Path, sys.argv[1:])
if target.exists() or target.is_symlink():
    raise SystemExit(f'Output appeared during packaging; refusing to replace: {target}')
os.rename(source, target)
PY
printf 'iOS package validated: %s\n' "$output"
