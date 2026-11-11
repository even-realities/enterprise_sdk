#!/usr/bin/env bash
set -euo pipefail

# Build and verify a standalone Maven distribution; this script never invokes remote publishing.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk_root="$(cd "$script_dir/.." && pwd)"
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  printf 'Usage: %s VERSION [OUTPUT_DIR]\nEnvironment: JAVA_HOME, GRADLE_CMD (executable path).\n' "$0"
  exit 0
fi
if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf 'Usage: %s VERSION [OUTPUT_DIR]\n' "$0" >&2
  exit 2
fi

sdk_version="$1"
output="${2:-$sdk_root/build/distribution/$sdk_version/android}"
# Reject malformed coordinates and existing destinations before creating staging files or building.
output="$(python3 - "$sdk_version" "$output" "$sdk_root" <<'PY'
from pathlib import Path
import re, sys
version, destination, repository = sys.argv[1:]
match = re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?', version)
if not match or (match[4] and any(p.isdigit() and len(p) > 1 and p[0] == '0' for p in match[4].split('.'))):
    raise SystemExit('VERSION must be SemVer without build metadata, for example 0.1.0-beta.1')
if not destination or '\n' in destination or '\r' in destination:
    raise SystemExit('OUTPUT_DIR must be nonempty and contain no line breaks')
path = Path(destination).expanduser()
if path.exists() or path.is_symlink():
    raise SystemExit(f'OUTPUT_DIR already exists; choose a new destination: {path}')
path = path.resolve()
repository = Path(repository).resolve()
if path.is_relative_to(repository) and not path.is_relative_to(repository / 'build'):
    raise SystemExit('Repository output must be under build/; source directories are not distribution destinations')
print(path)
PY
)"
gradle_cmd="${GRADLE_CMD:-$sdk_root/native/android/gradlew}"
if ! command -v "$gradle_cmd" >/dev/null 2>&1; then
  printf 'Gradle executable not found: %s\n' "$gradle_cmd" >&2
  exit 2
fi

source_commit="$(bash "$script_dir/native_release_source.sh")"
mkdir -p "$(dirname "$output")"
staging="$(mktemp -d "$(dirname "$output")/.android-package.XXXXXX")"
# Only remove the directory created above, never the caller's output path or audited binaries.
trap 'rm -rf "$staging"' EXIT
"$gradle_cmd" -p "$sdk_root/native/android" --no-daemon \
  -PsdkVersion="$sdk_version" -PsdkStandaloneDistribution=true \
  -PsdkMavenRepository="$staging/maven" \
  testDebugUnitTest lintRelease assembleRelease \
  publishReleasePublicationToMavenRepository 2>&1 | tee "$staging/build.log"

mkdir -p "$staging/licenses"
cp "$sdk_root/LICENSE" "$staging/LICENSE"
cp "$sdk_root/NOTICE" "$staging/NOTICE"
cp "$sdk_root/native/licenses/ANDROID_THIRD_PARTY_NOTICES.md" "$staging/THIRD_PARTY_NOTICES.md"
cp "$sdk_root/native/licenses/flutter_ezw_ble_LICENSE" "$staging/licenses/"
cp "$sdk_root/native/licenses/liblc3_LICENSE" "$staging/licenses/"
python3 - "$sdk_root" "$staging" "$output" "$sdk_version" "$gradle_cmd" <<'PY'
from pathlib import Path
import hashlib, io, json, os, subprocess, sys, xml.etree.ElementTree as ET, zipfile
root, stage, output = map(Path, sys.argv[1:4])
version, gradle = sys.argv[4:]
artifact = 'even-devices-native-android'
coordinate = f'com.evenrealities.devices:{artifact}:{version}'
release = stage / 'maven/com/evenrealities/devices' / artifact / version
stem = release / f'{artifact}-{version}'
aar, pom, module = (Path(str(stem) + suffix) for suffix in ('.aar', '.pom', '.module'))
for path in (aar, pom, module):
    if not path.is_file():
        raise SystemExit(f'Missing distribution artifact: {path.name}')

# Check consumer-facing metadata and payload together before making the distribution visible.
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
model = ET.parse(pom).getroot()
assert model.findtext('m:groupId', namespaces=ns) == 'com.evenrealities.devices'
assert model.findtext('m:artifactId', namespaces=ns) == artifact
assert model.findtext('m:version', namespaces=ns) == version
assert model.findtext('m:packaging', namespaces=ns) == 'aar'
assert model.findtext('m:url', namespaces=ns) == 'https://github.com/even-realities/enterprise_sdk'
assert model.findtext('m:scm/m:url', namespaces=ns) == 'https://github.com/even-realities/enterprise_sdk'
assert model.findtext('m:licenses/m:license/m:name', namespaces=ns) == 'The Apache License, Version 2.0'
assert model.findtext('m:licenses/m:license/m:url', namespaces=ns) == 'https://www.apache.org/licenses/LICENSE-2.0'
metadata = json.loads(module.read_text())
assert metadata['component']['version'] == version
dependencies = {(d['group'], d['module']) for v in metadata['variants'] for d in v.get('dependencies', [])}
assert ('com.google.code.gson', 'gson') in dependencies
assert ('org.jetbrains.kotlinx', 'kotlinx-coroutines-android') in dependencies
with zipfile.ZipFile(aar) as archive:
    with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as classes:
        names = classes.namelist()
        assert 'com/evenrealities/devices/EvenDevicesAndroidBle.class' in names
        assert not any(name.startswith('io/flutter/') for name in names)
        # Verify bundled legal text, not just filenames, before finalizing the AAR.
        assert classes.read('META-INF/even-devices-LICENSE.txt') == (root / 'LICENSE').read_bytes()
        assert classes.read('META-INF/even-devices-NOTICE.txt') == (root / 'NOTICE').read_bytes()
        assert classes.read('META-INF/THIRD_PARTY_NOTICES.md') == (stage / 'THIRD_PARTY_NOTICES.md').read_bytes()
        assert (root / 'third_party/flutter_ezw_utils/LICENSE').read_bytes() == classes.read('META-INF/licenses/flutter_ezw_ble_LICENSE')
        assert classes.read('META-INF/licenses/liblc3_LICENSE') == (root / 'native/licenses/liblc3_LICENSE').read_bytes()
        assert classes.read('META-INF/licenses/flutter_ezw_ble_LICENSE') == (root / 'native/licenses/flutter_ezw_ble_LICENSE').read_bytes()

    for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
        assert f'jni/{abi}/libeven_lc3.so' in archive.namelist()
        payload = archive.read(f'jni/{abi}/libeg3.so')
        original = (root / f'third_party/flutter_ezw_lc3/android/src/main/jniLibs/{abi}/liblc3.so').read_bytes()
        assert payload == original.replace(b'liblc3.so\0', b'libeg3.so\0')
    assert not any(name.endswith('/liblc3.so') for name in archive.namelist())

def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()

# A dirty flag and a source digest distinguish this local build from the recorded base commit.
sources = sorted(p for p in (root / 'native/android').rglob('*') if p.is_file()
                 and not any(part in {'.gradle', '.kotlin', '.cxx', 'build'} for part in p.relative_to(root / 'native/android').parts))
sources += sorted(p for p in (root / 'native/codec').rglob('*') if p.is_file())
sources += sorted((root / 'third_party/flutter_ezw_lc3/android/src/main/jniLibs').glob('*/liblc3.so'))
sources += sorted((root / 'third_party/flutter_ezw_lc3/lib/lc3_include').glob('*.h'))
sources += [root / 'native/tests/android_lc3_binary_parity_test.c', root / 'tool/test_native_lc3.sh', root / 'native/tests/lc3_decoder_test.c', *sorted((root / 'native/tests/fixtures').glob('g2_audio_*')), root / 'native/ios/Sources/EvenLC3/even_lc3.c', root / 'native/ios/Sources/EvenLC3/include/even_lc3.h', root / 'native/licenses/liblc3_LICENSE']
sources += [root / 'native/licenses/ANDROID_THIRD_PARTY_NOTICES.md', root / 'tool/native_release_source.sh', root / 'tool/package_android_sdk.sh', root / 'third_party/flutter_ezw_utils/LICENSE']
sources += [root / 'LICENSE', root / 'NOTICE', root / 'native/licenses/flutter_ezw_ble_LICENSE']
digest = hashlib.sha256()
for source in sources:
    digest.update(str(source.relative_to(root)).encode())
    digest.update(b'\0')
    digest.update(source.read_bytes())
    digest.update(b'\0')
java = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else 'java'
java_result = subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True)
gradle_result = subprocess.check_output([gradle, '--version'], text=True)
(stage / 'PROVENANCE.json').write_text(json.dumps({
    'coordinate': coordinate, 'license': 'Apache-2.0', 'source_commit': git('rev-parse', 'HEAD'),
    'source_tree': git('rev-parse', 'HEAD^{tree}'),
    'source_repository': os.environ.get('SDK_SOURCE_REPOSITORY', 'https://github.com/even-realities/enterprise_sdk'),
    'working_tree_dirty': bool(git('status', '--porcelain', '--untracked-files=all')),
    'android_source_sha256': digest.hexdigest(),
    'gradle': next(line for line in gradle_result.splitlines() if line.startswith('Gradle ')),
    'java': (java_result.stderr or java_result.stdout).strip(),
    'verification': ['testDebugUnitTest', 'lintRelease', 'assembleRelease',
                     'publishReleasePublicationToMavenRepository', 'AAR and Maven metadata checks'],
    'remote_published': False,
}, indent=2) + '\n')
(stage / 'README.md').write_text(f'''# Local Android Maven distribution

Coordinate: `{coordinate}`

Add `{(output / 'maven').as_uri()}` as a Maven repository in the Android host,
and keep google() / mavenCentral() for the SDK's declared dependencies.
Use `implementation("{coordinate}")`. Bluetooth permissions are requested by
the host. This package has passed local unit tests, lint and artifact checks;
it has not been uploaded or accepted on physical G2 hardware by this script.

AGP 8.7.3 currently warns that compileSdk 36 is newer than its tested API 35.
See docs/REPOSITORY_MIGRATION.md for repository migration and immutable-release boundaries.
''')
# Hash the final local payload after notices and provenance are written.
checksums = []
for path in sorted(p for p in stage.rglob('*') if p.is_file()):
    checksums.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(stage)}')
(stage / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
print(f'Validated local Maven coordinate: {coordinate}')
PY

bash "$script_dir/native_release_source.sh" "$source_commit" >/dev/null
# Rename the staged directory directly; unlike mv, an existing directory cannot absorb it.
python3 - "$staging" "$output" <<'PY'
from pathlib import Path
import os, sys
source, target = map(Path, sys.argv[1:])
if target.exists() or target.is_symlink():
    raise SystemExit(f'OUTPUT_DIR appeared during packaging; refusing to overwrite: {target}')
os.rename(source, target)
PY
printf 'Android SDK packaged locally: %s\n' "$output"
