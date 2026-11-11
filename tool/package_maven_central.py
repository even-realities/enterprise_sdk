#!/usr/bin/env python3
"""Prepare an immutable Maven Central bundle from the audited binary SDK.

This command never uploads, rebuilds the AAR, or creates a signing identity.
Input is the standalone Android packaging directory, not a Flutter plugin.
The sources classifier reads this repository at the exact clean build revision.
"""

import argparse
import hashlib
import html
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import tarfile
import xml.etree.ElementTree as ET
import zipfile


GROUP = "com.evenrealities.devices"
ARTIFACT = "even-devices-native-android"
# Native release metadata points to its own repository, independently of Flutter.
REPOSITORY = "https://github.com/even-realities/enterprise_sdk"
MAVEN_NS = "http://maven.apache.org/POM/4.0.0"
HASHES = ("md5", "sha1", "sha256", "sha512")
# Migration reuses beta.14 bytes; Central versions are immutable and not re-issued.
IMMUTABLE_CENTRAL_VERSIONS = frozenset({"0.1.0-beta.14"})
ET.register_namespace("", MAVEN_NS)
ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")


class BundleError(ValueError):
    """A failed invariant leaves the requested output directory unpublished."""


def validate_version(version):
    """Keep coordinates and filesystem names restricted to immutable SemVer."""
    match = re.fullmatch(
        r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
        r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?", version
    )
    if not match or any(
        item.isdigit() and len(item) > 1 and item.startswith("0")
        for item in (match[4] or "").split(".")
    ) or version.endswith("-SNAPSHOT"):
        raise BundleError("Version must be release SemVer without build metadata.")
    return version


def default_version(root):
    """Read the native repository version without a Flutter/YAML dependency."""
    return validate_version((root / "VERSION").read_text().strip())


def output_path(root, destination):
    """Refuse overwrite and source-tree destinations before creating staging."""
    path = Path(destination).expanduser()
    if any(ord(character) < 32 for character in str(path)):
        raise BundleError("Output path must not contain control characters.")
    if path.exists() or path.is_symlink():
        raise BundleError("Output directory already exists; choose a new path.")
    path = path.resolve()
    if path == root or path in root.parents or (
        path.is_relative_to(root) and not path.is_relative_to(root / "build")
    ):
        raise BundleError("Output inside this checkout must be below build/.")
    return path


def verify_manifest(distribution):
    """Check the audited inventory before any input bytes enter a new bundle."""
    entries = {}
    inventory = distribution / "SHA256SUMS"
    if inventory.is_symlink():
        raise BundleError("Distribution inventory must not be a symlink.")
    for line in inventory.read_text().splitlines():
        try:
            digest, name = line.split("  ", 1)
        except ValueError as error:
            raise BundleError("Malformed artifact checksum manifest.") from error
        relative = PurePosixPath(name)
        if (not re.fullmatch(r"[0-9a-f]{64}", digest) or name in entries
                or relative.is_absolute() or ".." in relative.parts or "\\" in name
                or str(relative) != name):
            raise BundleError("Unsafe or duplicate artifact manifest entry.")
        path = distribution / name
        if path.is_symlink() or not path.resolve().is_relative_to(distribution):
            raise BundleError("Artifact links outside the audited inventory are forbidden.")
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise BundleError(f"Artifact checksum mismatch: {name}")
        entries[name] = digest
    if not entries:
        raise BundleError("Artifact checksum manifest is empty.")
    return entries


def checked_zip_inventory(archive):
    """Reject aliased or unsafe archive entries before interpreting SDK bytes."""
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise BundleError("Duplicate archive entries are forbidden.")
    for info in archive.infolist():
        name = info.filename
        relative = PurePosixPath(name)
        if (not name or relative.is_absolute() or ".." in relative.parts or
                "\\" in name or relative.as_posix() != name.rstrip("/") or
                (info.external_attr >> 16) & 0o170000 == 0o120000):
            raise BundleError("Unsafe SDK archive entry.")


def verify_inputs(root, distribution, version):
    """Cross-check POM, module metadata, sidecars, payload and legal notices."""
    manifest = verify_manifest(distribution)
    relative = Path("maven") / GROUP.replace(".", "/") / ARTIFACT / version
    stem = f"{ARTIFACT}-{version}"
    files = {suffix: distribution / relative / f"{stem}.{suffix}" for suffix in ("aar", "pom", "module")}
    for path in files.values():
        if path.relative_to(distribution).as_posix() not in manifest:
            raise BundleError("Release artifact is absent from the audited manifest.")
        for algorithm in HASHES:
            sidecar = path.with_name(path.name + "." + algorithm)
            if not sidecar.is_file() or sidecar.relative_to(distribution).as_posix() not in manifest:
                raise BundleError(f"Missing audited {algorithm} sidecar: {path.name}")
            if sidecar.read_text().strip() != hashlib.new(algorithm, path.read_bytes()).hexdigest():
                raise BundleError(f"Sidecar mismatch: {sidecar.name}")
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    pom = ET.fromstring(files["pom"].read_bytes(), parser=parser)
    text = lambda name: pom.findtext(f"{{{MAVEN_NS}}}{name}")
    if (text("groupId"), text("artifactId"), text("version"), text("packaging")) != (GROUP, ARTIFACT, version, "aar"):
        raise BundleError("POM coordinates do not match the selected SDK.")
    if pom.findtext(f"{{{MAVEN_NS}}}licenses/{{{MAVEN_NS}}}license/{{{MAVEN_NS}}}url") != "https://www.apache.org/licenses/LICENSE-2.0":
        raise BundleError("POM must retain the Apache 2.0 license.")
    module = json.loads(files["module"].read_text())
    component = module.get("component", {})
    if (component.get("group"), component.get("module"), component.get("version")) != (GROUP, ARTIFACT, version):
        raise BundleError("Gradle module coordinates do not match the selected SDK.")
    variants = module.get("variants", [])
    if not variants:
        raise BundleError("Gradle module has no publication variants.")
    aar = files["aar"].read_bytes()
    for variant in variants:
        if not variant.get("files"):
            raise BundleError("Gradle variant has no AAR.")
        for artifact in variant["files"]:
            if artifact.get("name") != stem + ".aar" or artifact.get("url") != stem + ".aar" or artifact.get("size") != len(aar):
                raise BundleError("Gradle module refers to a different AAR.")
            if any(artifact.get(algorithm) != hashlib.new(algorithm, aar).hexdigest() for algorithm in HASHES):
                raise BundleError("Gradle module AAR checksum mismatch.")
    with zipfile.ZipFile(io.BytesIO(aar)) as archive:
        checked_zip_inventory(archive)
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            checked_zip_inventory(classes)
            names = classes.namelist()
            if "com/evenrealities/devices/EvenDevicesAndroidBle.class" not in names:
                raise BundleError("AAR lacks the standalone SDK entry point.")
            if any(name.startswith("io/flutter/") or "FlutterEzwBlePlugin" in name for name in names):
                raise BundleError("AAR contains Flutter runtime classes.")
            inventory = classes.read("META-INF/THIRD_PARTY_NOTICES.md").decode()
            if inventory != (distribution / "THIRD_PARTY_NOTICES.md").read_text():
                raise BundleError("AAR component notices differ from the distribution.")
            if any(name not in inventory for name in ("flutter_ezw_ble", "flutter_ezw_utils", "liblc3")):
                raise BundleError("Incomplete native AAR component notices.")
            for entry, name in (
                ("META-INF/even-devices-LICENSE.txt", "LICENSE"),
                ("META-INF/even-devices-NOTICE.txt", "NOTICE"),
                ("META-INF/licenses/flutter_ezw_ble_LICENSE", "native/licenses/flutter_ezw_ble_LICENSE"),
                ("META-INF/licenses/flutter_ezw_ble_LICENSE", "third_party/flutter_ezw_utils/LICENSE"),
                ("META-INF/licenses/liblc3_LICENSE", "native/licenses/liblc3_LICENSE"),
            ):
                if classes.read(entry) != (root / name).read_bytes():
                    raise BundleError("AAR license notice differs from the audited distribution.")
    for required in ("PROVENANCE.json", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md",
                     "licenses/flutter_ezw_ble_LICENSE", "licenses/liblc3_LICENSE"):
        if required not in manifest:
            raise BundleError(f"Missing audited native provenance or notice: {required}")
    for source, delivered in (("LICENSE", "LICENSE"), ("NOTICE", "NOTICE"),
            ("native/licenses/ANDROID_THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
            ("native/licenses/flutter_ezw_ble_LICENSE", "licenses/flutter_ezw_ble_LICENSE"),
            ("native/licenses/liblc3_LICENSE", "licenses/liblc3_LICENSE")):
        if (root / source).read_bytes() != (distribution / delivered).read_bytes():
            raise BundleError("Distribution notice differs from the source repository.")
    return files, pom


def central_pom(pom):
    """Enrich a staged POM while retaining original dependencies and comments."""
    def element(name, text=None, parent=None):
        node = ET.Element(f"{{{MAVEN_NS}}}{name}")
        node.text = text
        if parent is not None:
            parent.append(node)
        return node
    for name in ("url", "developers", "scm"):
        existing = pom.find(f"{{{MAVEN_NS}}}{name}")
        if existing is not None:
            pom.remove(existing)
    url = element("url", REPOSITORY)
    developers = element("developers")
    developer = element("developer", parent=developers)
    for name, value in (
        ("id", "even-realities"), ("name", "Even Realities"),
        ("url", "https://www.evenrealities.com"),
        ("organization", "Hong Kong Even Realities Limited"),
        ("organizationUrl", "https://www.evenrealities.com"),
    ):
        element(name, value, developer)
    scm = element("scm")
    for name, value in (
        ("connection", "scm:git:" + REPOSITORY + ".git"),
        ("developerConnection", "scm:git:ssh://git@github.com/even-realities/enterprise_sdk.git"),
        ("url", REPOSITORY),
    ):
        element(name, value, scm)
    dependencies = pom.find(f"{{{MAVEN_NS}}}dependencies")
    index = list(pom).index(dependencies) if dependencies is not None else len(pom)
    for offset, node in enumerate((url, developers, scm)):
        pom.insert(index + offset, node)
    ET.indent(pom, space="  ")
    return ET.tostring(pom, encoding="utf-8", xml_declaration=True) + b"\n"


def write_jar(path, entries):
    """Stable entry timestamps keep unsigned preparation reproducible."""
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)


def android_sources(root, distribution, version):
    """Read tracked implementation files at the distribution's clean build commit."""
    record = json.loads((distribution / "PROVENANCE.json").read_text())
    commit = record.get("source_commit", "")
    tree = record.get("source_tree", "")
    if (record.get("working_tree_dirty") is not False or
            record.get("coordinate") != f"{GROUP}:{ARTIFACT}:{version}" or
            not re.fullmatch(r"[a-f0-9]{40}", commit) or
            not re.fullmatch(r"[a-f0-9]{40}", tree)):
        raise BundleError("Android sources require the matching clean native build record.")
    git = ["git", "-C", str(root)]
    def read(*args):
        try:
            return subprocess.check_output(git + list(args), stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            raise BundleError("Recorded native source commit is unavailable in this checkout.") from None
    if (read("rev-parse", "HEAD").decode().strip() != commit or
            read("rev-parse", commit + "^{tree}").decode().strip() != tree):
        raise BundleError("Native source commit/tree differs from the build record; check out the recorded revision.")
    if read("status", "--porcelain", "--untracked-files=all").strip():
        raise BundleError("Native Central packaging requires a clean source checkout.")
    # Package-relative Kotlin paths let IDEs attach sources; retain C/JNI paths
    # for inspection. Vendor codec binaries remain separately attributed inputs.
    source = read("archive", commit, "native/android/src/main", "native/ios/Sources/EvenLC3")
    files = {}
    with tarfile.open(fileobj=io.BytesIO(source)) as archive:
        for member in archive:
            if member.isfile() and PurePosixPath(member.name).suffix in (".kt", ".java", ".cpp", ".h", ".c"):
                name = member.name.removeprefix("native/android/src/main/kotlin/")
                files[name] = archive.extractfile(member).read()
    if "com/evenrealities/devices/EvenDevicesAndroidBle.kt" not in files:
        raise BundleError("Source revision lacks the Android SDK entry point.")
    return files, record


def sign_and_verify(files, fingerprint, gpg):
    """Use an existing GPG identity and verify its signatures before finalizing."""
    if not re.fullmatch(r"[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64}", fingerprint):
        raise BundleError("Signing key must be a full GPG fingerprint.")
    for artifact in files:
        signature = artifact.with_name(artifact.name + ".asc")
        signed = subprocess.run(
            [gpg, "--batch", "--yes", "--armor", "--local-user", fingerprint,
             "--output", str(signature), "--detach-sign", str(artifact)], capture_output=True
        )
        if signed.returncode != 0:
            raise BundleError("GPG signing failed; unlock the existing key in your GPG agent.")
        verified = subprocess.run(
            [gpg, "--batch", "--status-fd=1", "--verify", str(signature), str(artifact)],
            capture_output=True, text=True,
        )
        identities = [line.split() for line in verified.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
        if verified.returncode != 0 or not any(fingerprint.upper() in (line[2].upper(), line[-1].upper()) for line in identities):
            raise BundleError("GPG signature verification did not confirm the requested key.")


def build_bundle(root, version, destination, *, distribution=None, unsigned=False, signing_key=None, gpg="gpg"):
    """Validate first, stage privately, and expose the output in one rename."""
    root = Path(root).resolve()
    version = validate_version(version)
    if unsigned == bool(signing_key):
        raise BundleError("Choose exactly one of --unsigned or --signing-key.")
    if signing_key and version in IMMUTABLE_CENTRAL_VERSIONS:
        raise BundleError("This version is already published; use a new version for Central signing.")
    output = output_path(root, destination)
    if distribution is None:
        distribution = root / "build/distribution" / version / "android"
    distribution = Path(distribution).expanduser()
    if distribution.is_symlink() or not distribution.is_dir():
        raise BundleError("Distribution must be an existing, non-symlink Android package directory.")
    distribution = distribution.resolve()
    if output.is_relative_to(distribution) or distribution.is_relative_to(output):
        raise BundleError("Central output and input distribution must not overlap.")
    files, pom = verify_inputs(root, distribution, version)
    inventory_bytes = (distribution / "SHA256SUMS").read_bytes()
    sources, provenance = android_sources(root, distribution, version)
    guide = (root / "docs/enterprise/enterprise-android-sdk.md").read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".central-package-", dir=output.parent) as work:
        stage = Path(work) / "package"
        relative = Path(GROUP.replace(".", "/")) / ARTIFACT / version
        release = stage / "maven" / relative
        release.mkdir(parents=True)
        stem = f"{ARTIFACT}-{version}"
        for suffix in ("aar", "module"):
            shutil.copyfile(files[suffix], release / files[suffix].name)
        (release / (stem + ".pom")).write_bytes(central_pom(pom))
        notices = {"LICENSE": (root / "LICENSE").read_bytes(),
            "NOTICE": (root / "NOTICE").read_bytes(),
            "THIRD_PARTY_NOTICES.md": (root / "native/licenses/ANDROID_THIRD_PARTY_NOTICES.md").read_bytes(),
            "licenses/flutter_ezw_ble_LICENSE": (root / "native/licenses/flutter_ezw_ble_LICENSE").read_bytes(),
            "licenses/flutter_ezw_utils_LICENSE": (root / "third_party/flutter_ezw_utils/LICENSE").read_bytes(),
            "licenses/liblc3_LICENSE": (root / "native/licenses/liblc3_LICENSE").read_bytes()}

        write_jar(release / (stem + "-sources.jar"), {**notices, **sources})
        page = "<!doctype html><meta charset=utf-8><title>Even Devices Android SDK</title><pre>" + html.escape(guide.decode()) + "</pre>\n"
        write_jar(release / (stem + "-javadoc.jar"), {**notices, "README.md": guide, "index.html": page.encode()})
        primary = sorted(release.iterdir())
        for artifact in primary:
            for algorithm in HASHES:
                artifact.with_name(artifact.name + "." + algorithm).write_text(
                    hashlib.new(algorithm, artifact.read_bytes()).hexdigest() + "\n"
                )
        if signing_key:
            sign_and_verify(primary, signing_key, gpg)
        # Central owns repository-wide metadata; upload only this component path.
        bundle_name = f"{ARTIFACT}-{version}-central{'-unsigned' if unsigned else ''}.zip"
        write_jar(stage / bundle_name, {
            path.relative_to(stage / "maven").as_posix(): path.read_bytes()
            for path in sorted(release.iterdir())
        })
        record = {
            "coordinate": f"{GROUP}:{ARTIFACT}:{version}", "version": version,
            "bundle": bundle_name,
            "bundle_sha256": hashlib.sha256((stage / bundle_name).read_bytes()).hexdigest(),
            "artifact_sha256": hashlib.sha256(files["aar"].read_bytes()).hexdigest(),
            "signed": not unsigned, "upload_ready": not unsigned,
            "central_validation": "not_run", "public_key_distribution": "not_checked",
            "signing_key": signing_key.upper() if signing_key else None,
            "source_distribution": "native-build-commit",
            "source_commit": provenance["source_commit"], "source_tree": provenance["source_tree"],
            "artifacts": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in primary},
            "notice": "NOT UPLOAD-READY: unsigned inspection bundle." if unsigned else
                      "Locally verified signatures; Central namespace and public-key availability still require verification.",
        }
        (stage / "release.json").write_text(json.dumps(record, indent=2) + "\n")
        # Validate again after copying/signing: a changing local input cannot
        # silently become a signed bundle attributed to an earlier inventory.
        if (distribution / "SHA256SUMS").read_bytes() != inventory_bytes:
            raise BundleError("Distribution inventory changed during Central packaging.")
        verify_manifest(distribution)
        # A concurrent process must not turn the destination into an overwrite.
        if output.exists() or output.is_symlink():
            raise BundleError("Output appeared during packaging; refusing overwrite.")
        stage.rename(output)
    return output


def main(argv=None):
    """The default remains fail-closed: signing or inspection must be explicit."""
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default=default_version(root))
    parser.add_argument("--distribution", type=Path, help="Output directory from tool/package_android_sdk.sh.")
    parser.add_argument("--output-dir", type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--unsigned", action="store_true", help="Inspection only; cannot upload to Central.")
    mode.add_argument("--signing-key", metavar="FINGERPRINT", help="Existing GPG signing-key fingerprint.")
    parser.add_argument("--gpg", default="gpg", help="GPG executable; defaults to gpg.")
    args = parser.parse_args(argv)
    destination = args.output_dir or root / "build/central" / args.version / ("unsigned" if args.unsigned else "signed")
    try:
        output = build_bundle(root, args.version, destination, distribution=args.distribution, unsigned=args.unsigned, signing_key=args.signing_key, gpg=args.gpg)
    except (BundleError, OSError, ET.ParseError, json.JSONDecodeError, zipfile.BadZipFile, KeyError) as error:
        parser.exit(1, f"Cannot prepare Central bundle: {error}\n")
    print(output)
    if args.unsigned:
        print("NOT UPLOAD-READY: unsigned inspection bundle; no remote upload was attempted.")


if __name__ == "__main__":
    main()
