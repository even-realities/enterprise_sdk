#!/usr/bin/env python3
"""Preserve production codec; compile its embedded LLVM for missing Apple targets.

The device and x86 simulator objects are used unchanged. New platform objects
are produced by the compiler backend from original embedded LLVM bitcode; no
Mach-O platform load command is rewritten. All work stays in a private temp dir.
"""
from pathlib import Path
import hashlib
import json
import os
import plistlib
import shutil
import struct
import sys
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
ARCHIVE = ROOT / 'third_party/flutter_ezw_lc3/ios/Classes/framework/liblc3.a'
OUTPUT = ROOT / 'native/ios/Codec/EvenLC3Core.xcframework'


def run(*args, cwd=None):
    subprocess.run(list(map(str, args)), cwd=cwd, check=True, env={**os.environ, "ZERO_AR_DATE": "1"})


def bitcode(path):
    """Read the full LLVM section with explicit 64-bit Mach-O bounds checks."""
    data = path.read_bytes()
    if data[:4] != b'\xcf\xfa\xed\xfe':
        raise ValueError(f'Not a little-endian Mach-O 64 object: {path}')
    offset = 32
    for _ in range(struct.unpack_from('<I', data, 16)[0]):
        command, size = struct.unpack_from('<II', data, offset)
        if size < 8 or offset + size > len(data):
            raise ValueError('Invalid Mach-O load command')
        if command == 0x19:
            for index in range(struct.unpack_from('<I', data, offset + 64)[0]):
                section = offset + 72 + index * 80
                if data[section:section + 16].rstrip(b'\0') == b'__bitcode':
                    length = struct.unpack_from('<Q', data, section + 40)[0]
                    start = struct.unpack_from('<I', data, section + 48)[0]
                    if length < 4 or start + length > len(data):
                        raise ValueError('Missing full embedded LLVM bitcode')
                    return data[start:start + length]
        offset += size
    raise ValueError(f'No embedded bitcode: {path}')


def main():
    with tempfile.TemporaryDirectory(prefix='even-apple-lc3-') as temporary:
        work = Path(temporary)
        objects = {}
        for arch in ['arm64', 'x86_64']:
            directory = work / arch
            directory.mkdir()
            run('xcrun', 'lipo', ARCHIVE, '-thin', arch, '-output', directory / 'original.a')
            run('ar', '-x', directory / 'original.a', cwd=directory)
            objects[arch] = sorted(directory.glob('*.o'))
            assert len(objects[arch]) == 12, 'Unexpected pinned archive member count'

        def compile_ir(arch, target, sdk, destination):
            destination.mkdir()
            sdk_path = subprocess.check_output(['xcrun', '--sdk', sdk, '--show-sdk-path'], text=True).strip()
            result = []
            for original in objects[arch]:
                source = destination / (original.stem + '.bc')
                source.write_bytes(bitcode(original))
                output = destination / original.name
                run('xcrun', '--sdk', sdk, 'clang', '-target', target, '-isysroot', sdk_path,
                    '-O2', '-Wno-override-module', '-c', source, '-o', output)
                result.append(output)
            return result

        def archive(path, members):
            run('xcrun', 'libtool', '-static', '-o', path, *members)

        archive(work / 'device.a', objects['arm64'])
        archive(work / 'sim-x86.a', objects['x86_64'])
        archive(work / 'sim-arm.a', compile_ir('arm64', 'arm64-apple-ios15.0-simulator',
                'iphonesimulator', work / 'sim-arm'))
        run('xcrun', 'lipo', '-create', work / 'sim-arm.a', work / 'sim-x86.a', '-output', work / 'simulator.a')
        for arch in ['arm64', 'x86_64']:
            archive(work / f'mac-{arch}.a', compile_ir(arch, f'{arch}-apple-macos13.0', 'macosx', work / f'mac-{arch}'))
        run('xcrun', 'lipo', '-create', work / 'mac-arm64.a', work / 'mac-x86_64.a', '-output', work / 'macos.a')
        headers = work / 'Headers'
        headers.mkdir()
        shutil.copy(ROOT / 'third_party/flutter_ezw_lc3/lib/lc3_include/lc3.h', headers)
        shutil.copy(ROOT / 'third_party/flutter_ezw_lc3/lib/lc3_include/lc3_private.h', headers)
        (headers / 'module.modulemap').write_text('module EvenLC3Core {\n  header "lc3.h"\n  export *\n}\n')
        result = work / 'EvenLC3Core.xcframework'
        run('xcodebuild', '-create-xcframework', '-library', work / 'device.a', '-headers', headers,
            '-library', work / 'simulator.a', '-headers', headers,
            '-library', work / 'macos.a', '-headers', headers, '-output', result)
        # xcodebuild emits dictionary keys in nondeterministic order; normalize metadata.
        plist = result / 'Info.plist'
        info = plistlib.loads(plist.read_bytes())
        info['AvailableLibraries'].sort(key=lambda library: library['LibraryIdentifier'])
        plist.write_bytes(plistlib.dumps(info, sort_keys=True))
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        shutil.copytree(result, OUTPUT)
        # Pin every generated input to the original archive and backend toolchain.
        manifest = OUTPUT.parent / 'source.sha256'
        manifest.write_text(hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() + '  ' + str(ARCHIVE.relative_to(ROOT)) + '\n')
        metadata = {
            'original_archive': str(ARCHIVE.relative_to(ROOT)),
            'original_archive_sha256': hashlib.sha256(ARCHIVE.read_bytes()).hexdigest(),
            'compiler': subprocess.check_output(['xcrun', 'clang', '--version'], text=True).strip(),
            'slices': {'ios-arm64': 'original arm64 iOS objects unchanged',
                       'ios-arm64_x86_64-simulator': 'original x86_64 simulator objects; original arm64 embedded LLVM compiled for iOS simulator',
                       'macos-arm64_x86_64': 'original per-architecture embedded LLVM compiled for macOS'},
            'platform_metadata_rewritten': False,
            'files_sha256': {str(p.relative_to(OUTPUT)): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in sorted(OUTPUT.rglob('*')) if p.is_file()},
        }
        (OUTPUT.parent / 'PROVENANCE.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print(f'Prepared original-codec Apple slices: {OUTPUT}')


def verify():
    """Fail closed when committed codec slices or the pinned archive drift."""
    metadata = json.loads((OUTPUT.parent / 'PROVENANCE.json').read_text())
    assert hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() == metadata['original_archive_sha256']
    actual = {str(p.relative_to(OUTPUT)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(OUTPUT.rglob('*')) if p.is_file()}
    assert actual == metadata['files_sha256'], 'Apple codec payload changed; regenerate and revalidate'
    assert not metadata['platform_metadata_rewritten']
    print('Original Apple codec archive and generated slices verified')


if __name__ == '__main__':
    if sys.argv[1:] == ['--verify']:
        verify()
    elif sys.argv[1:]:
        raise SystemExit('Usage: prepare_apple_lc3.py [--verify]')
    else:
        main()
