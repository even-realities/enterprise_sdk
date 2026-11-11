"""Render the remote SwiftPM manifest without changing the validated local package."""

import json
import re
from urllib.parse import urlsplit


def validate_binary_url(url):
    """Reject transient or credential-bearing addresses before starting a build."""
    if any(ord(character) < 0x20 or ord(character) == 0x7f for character in url):
        raise ValueError('SPM_BINARY_URL must not contain control characters.')
    parts = urlsplit(url)
    if (parts.scheme != 'https' or not parts.hostname or parts.username
            or parts.password or parts.query or parts.fragment
            or not parts.path.endswith('.zip')):
        raise ValueError('SPM_BINARY_URL must be a fixed HTTPS ZIP URL without embedded credentials, query or fragment.')


def render_remote_manifest(local_manifest, url, checksum):
    """Keep Unicode literal: JSON's ASCII \\uXXXX escapes are invalid in Swift."""
    if url:
        validate_binary_url(url)
    if not re.fullmatch(r'[0-9a-f]{64}', checksum):
        raise ValueError('The final ZIP must have a SHA-256 checksum.')
    marker = 'path: "Frameworks/EvenDevicesSDK.xcframework"'
    if local_manifest.count(marker) != 1:
        raise ValueError('The local manifest must contain exactly one SDK binary target.')
    address = url or 'https://<download-host>/<version>/EvenDevicesSDK.xcframework.zip'
    # After rejecting control characters, JSON and Swift share the needed quote
    # and backslash escapes. Literal Unicode also preserves non-BMP URL paths.
    return local_manifest.replace(
        marker,
        'url: ' + json.dumps(address, ensure_ascii=False)
        + ', checksum: ' + json.dumps(checksum),
    )
