#!/usr/bin/env python3
"""Upload a signed SDK bundle to the fixed Sonatype Central Portal endpoint."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

from package_maven_central import IMMUTABLE_CENTRAL_VERSIONS, validate_version


PORTAL = 'https://central.sonatype.com/api/v1/publisher'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Do not forward the publishing credential to a redirected destination."""

    def redirect_request(self, request, file, code, message, headers, newurl):
        raise urllib.error.HTTPError(request.full_url, code, message, headers, file)


def deployment_id(value):
    """Only Portal UUIDs may enter deployment URL paths."""
    return str(uuid.UUID(value))


def authorization():
    """Read the Portal token pair at runtime; never put it in arguments or logs."""
    username = os.environ.get('CENTRAL_TOKEN_USERNAME', '')
    password = os.environ.get('CENTRAL_TOKEN_PASSWORD', '')
    if not username or not password:
        raise ValueError('Configure CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD from a Portal user token.')
    return 'Bearer ' + base64.b64encode(f'{username}:{password}'.encode()).decode()


def request(path, data=b'', content_type='application/json'):
    """Use authenticated POSTs only; do not retry a potentially accepted upload."""
    call = urllib.request.Request(
        PORTAL + path, data=data, method='POST',
        headers={'Authorization': authorization(), 'Content-Type': content_type,
                 'Accept': 'application/json', 'User-Agent': 'EvenDevicesSDK-Publisher'},
    )
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(call, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        # Responses can contain proxy diagnostics. Keep credentials and request
        # bodies out of the exception presented to a developer or CI log.
        code = error.code
        error.close()
        raise ValueError(f'Central Portal HTTP {code}; inspect the deployment in the Portal.') from None


def checked_bundle(directory):
    """Unsigned preparations and changed ZIP bytes must fail before networking."""
    directory = Path(directory).resolve()
    release = json.loads((directory / 'release.json').read_text())
    if release.get('upload_ready') is not True or not release.get('signing_key'):
        raise ValueError('This preparation is not signed/upload-ready. Build with --signing-key first.')
    name = release.get('bundle')
    if not isinstance(name, str) or Path(name).name != name or not name.endswith('.zip'):
        raise ValueError('Invalid release bundle filename.')
    archive = directory / name
    if archive.is_symlink():
        raise ValueError('The release bundle must not be a symlink.')
    data = archive.read_bytes()
    if hashlib.sha256(data).hexdigest() != release.get('bundle_sha256'):
        raise ValueError('The bundle changed after packaging.')
    coordinate = release.get('coordinate', '')
    parts = coordinate.split(':')
    if len(parts) != 3 or parts[0:2] != ['com.evenrealities.devices', 'even-devices-native-android']:
        raise ValueError('Unexpected SDK coordinate.')
    group, artifact, version = parts
    if version != release.get('version') or validate_version(version) != version:
        raise ValueError('Invalid SDK version.')
    if version in IMMUTABLE_CENTRAL_VERSIONS:
        raise ValueError('This version is already published; an existing Central release cannot be uploaded again.')
    prefix = group.replace('.', '/') + f'/{artifact}/{version}/'
    stem = f'{artifact}-{version}'
    primary = [stem + suffix for suffix in ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar')]
    # Validate the same byte snapshot that will be sent, even if a local process
    # replaces the file after we captured its digest.
    with zipfile.ZipFile(io.BytesIO(data)) as zipped:
        names = zipped.namelist()
        required = {prefix + file + suffix for file in primary
                    for suffix in ('', '.asc', '.md5', '.sha1', '.sha256', '.sha512')}
        if len(names) != len(set(names)) or set(names) != required:
            raise ValueError('Bundle inventory must include exactly the signed SDK files and their checksums.')
        for file in primary:
            content = zipped.read(prefix + file)
            for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                expected = hashlib.new(algorithm, content).hexdigest()
                if zipped.read(prefix + file + '.' + algorithm).decode().strip() != expected:
                    raise ValueError('Invalid artifact checksum inside bundle.')
            if not zipped.read(prefix + file + '.asc').startswith(b'-----BEGIN PGP SIGNATURE-----'):
                raise ValueError('Missing armored artifact signature.')
        if hashlib.sha256(zipped.read(prefix + stem + '.aar')).hexdigest() != release.get('artifact_sha256'):
            raise ValueError('Bundle no longer contains the recorded AAR.')
    return release, archive, data


def upload(directory, automatic=False):
    """A deployment ID proves upload acceptance, not successful publication."""
    release, archive, data = checked_bundle(directory)
    boundary = 'EvenDevicesSDK' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; '
            f'filename="{archive.name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
    body += data + f'\r\n--{boundary}--\r\n'.encode()
    query = urllib.parse.urlencode({
        'name': release['coordinate'],
        'publishingType': 'AUTOMATIC' if automatic else 'USER_MANAGED',
    })
    identifier = deployment_id(request('/upload?' + query, body, 'multipart/form-data; boundary=' + boundary).decode().strip())
    return {'deployment_id': identifier, 'coordinate': release['coordinate'],
            'state': 'UPLOADED', 'next': f'python3 tool/publish_maven_central.py status {identifier}'}


def status(identifier):
    """Return the server state so VALIDATED cannot be mistaken for PUBLISHED."""
    identifier = deployment_id(identifier)
    response = json.loads(request('/status?' + urllib.parse.urlencode({'id': identifier})))
    # Keep the output small and avoid echoing arbitrary server error text.
    return {'deployment_id': identifier, 'state': response['deploymentState'],
            'purls': response.get('purls', []), 'has_errors': bool(response.get('errors'))}


def publish(identifier):
    """Only ask Central to publish a deployment that has passed its validation."""
    result = status(identifier)
    if result['state'] != 'VALIDATED':
        raise ValueError(f"Deployment must be VALIDATED before publish; current state: {result['state']}")
    request('/deployment/' + deployment_id(identifier))
    return {'deployment_id': deployment_id(identifier), 'state': 'PUBLICATION_REQUESTED'}


def main():
    """Separate local signing, upload acceptance, validation and publication."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    send = commands.add_parser('upload', help='Upload an already signed package directory')
    send.add_argument('directory', type=Path)
    send.add_argument('--publish', action='store_true', help='Have Central publish automatically after validation')
    for name in ('status', 'publish'):
        sub = commands.add_parser(name)
        sub.add_argument('deployment_id')
    args = parser.parse_args()
    try:
        if args.command == 'upload':
            result = upload(args.directory, args.publish)
        elif args.command == 'status':
            result = status(args.deployment_id)
        else:
            result = publish(args.deployment_id)
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, urllib.error.URLError) as error:
        print(f'Publish failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
