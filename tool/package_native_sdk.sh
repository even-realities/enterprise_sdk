#!/usr/bin/env bash
set -euo pipefail

# Build both native distributions with one version. Platform scripts own their
# staging and verification; this entry point never uploads or creates Git tags.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: tool/package_native_sdk.sh VERSION [--platform all|android|ios] [--output-dir DIR]

Default output: build/distribution/VERSION/{android,ios}
Outputs must not exist. Use a new version or another output directory to rebuild.
JAVA_HOME selects the Android JDK; GRADLE_CMD can override the Gradle executable.
SDK_BUILD_NUMBER and SDK_BUNDLE_VERSION configure iOS bundle metadata.
USAGE
}

if [[ "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then
  usage
  exit 0
fi
if [[ $# -lt 1 ]]; then
  usage >&2
  exit 2
fi
sdk_release="$1"
shift
platform='all'
output_dir="$repository_root/build/distribution/$sdk_release"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform|--output-dir)
      if [[ $# -lt 2 || -z "$2" ]]; then
        printf 'Missing value for %s\n' "$1" >&2
        exit 2
      fi
      if [[ "$1" == '--platform' ]]; then platform="$2"; else output_dir="$2"; fi
      shift 2
      ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done
case "$platform" in
  all|android|ios) ;;
  *) printf 'Unsupported platform: %s\n' "$platform" >&2; exit 2 ;;
esac

# Validate the shared version before either platform can leave partial output.
# Numeric prerelease identifiers follow SemVer's no-leading-zero rule.
python3 - "$sdk_release" <<'PY'
import re
import sys

version = sys.argv[1]
match = re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?', version)
if not match or any(part.isdigit() and len(part) > 1 and part[0] == '0'
                    for part in (match.group(4) or '').split('.')):
    sys.exit('VERSION must be SemVer such as 0.1.0 or 0.1.0-beta.1 (without build metadata).')
PY

# Preflight every requested output before starting the first expensive build.
for target in android ios; do
  if [[ "$platform" != 'all' && "$platform" != "$target" ]]; then continue; fi
  if [[ -e "$output_dir/$target" || -L "$output_dir/$target" ]]; then
    printf 'Output already exists: %s\n' "$output_dir/$target" >&2
    exit 2
  fi
done
if [[ "$platform" == 'all' || "$platform" == 'android' ]]; then
  "$script_dir/package_android_sdk.sh" "$sdk_release" "$output_dir/android"
fi
if [[ "$platform" == 'all' || "$platform" == 'ios' ]]; then
  "$script_dir/package_ios_sdk.sh" "$sdk_release" "$output_dir/ios"
fi
printf 'Native SDK local distributions: %s\n' "$output_dir"
