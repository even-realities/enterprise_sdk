#!/usr/bin/env python3
"""Verify imported release identity separately from builds of the migrated source."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import urllib.request
import zipfile


def verify_inventory(root, inventory):
    """Reject unsafe/duplicate entries and mutation before trusting release metadata."""
    seen = set()
    for line in inventory.read_text().splitlines():
        digest, separator, name = line.partition('  ')
        path = PurePosixPath(name)
        if (not separator or not re.fullmatch(r'[0-9a-f]{64}', digest) or
                name in seen or path.is_absolute() or '..' in path.parts):
            raise ValueError('Unsafe, duplicate, or malformed checksum inventory entry')
        seen.add(name)
        source = root / name
        if (source.is_symlink() or not source.resolve().is_relative_to(root) or
                not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != digest):
            raise ValueError('Checksum or file identity mismatch: ' + name)
    if not seen:
        raise ValueError('Checksum inventory is empty')
    return seen


def verify(root, online=False):
    """Require the original bytes, public package identity, notices and source parity."""
    root = root.resolve()
    version = (root / 'VERSION').read_text().strip()
    release = json.loads((root / 'release.json').read_text())
    if version != release['version'] or release['license'] != 'Apache-2.0':
        raise ValueError('Release version/license mismatch')
    files = verify_inventory(root, root / 'checksums.sha256')
    for name in (release['upstream_source_archive'], release['ios']['zip']):
        if name not in files:
            raise ValueError('Release input missing from checksum inventory')
    expected = release['ios']
    package = (root / 'Package.swift').read_text()
    if (expected['product'] != 'EvenDevicesSDK' or
            expected['package'] != 'https://github.com/even-realities/enterprise_sdk.git' or
            f'path: "{expected["zip"]}"' not in package or
            hashlib.sha256((root / expected['zip']).read_bytes()).hexdigest() != expected['sha256']):
        raise ValueError('SPM manifest or original binary identity mismatch')
    with zipfile.ZipFile(root / expected['zip']) as archive:
        names = set(archive.namelist())
        prefix = 'EvenDevicesSDK.xcframework/'
        for name in ('Info.plist', 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md'):
            if prefix + name not in names:
                raise ValueError('Binary archive missing release metadata: ' + name)
    if version == '0.1.0-beta.14':
        # Repository migration is not a new beta.14 implementation. Future
        # runtime changes need a new version rather than a silently changed tag.
        verify_inventory(root, root / 'native/IMPORTED_RUNTIME.sha256')
    if online:
        # Fixed public artifact URL; never accept a caller-controlled download host.
        coordinate = f'com.evenrealities.devices:even-devices-native-android:{version}'
        path = (f'https://repo.maven.apache.org/maven2/com/evenrealities/devices/'
                f'even-devices-native-android/{version}/even-devices-native-android-{version}.aar')
        if release['android']['coordinate'] != coordinate or release['android']['url'] != path:
            raise ValueError('Unexpected Maven coordinate or public URL')
        with urllib.request.urlopen(path, timeout=60) as response:
            digest = hashlib.sha256(response.read()).hexdigest()
        if digest != release['android']['sha256']:
            raise ValueError('Maven Central AAR differs from original beta.14')
        print('Maven Central original AAR SHA-256 verified')
    print(f'Imported {version}: checksums, SPM binary, legal metadata and runtime source parity verified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--online', action='store_true', help='Also verify the original Maven Central AAR')
    args = parser.parse_args()
    verify(Path(__file__).resolve().parent.parent, args.online)


if __name__ == '__main__':
    main()
