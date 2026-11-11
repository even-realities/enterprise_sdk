"""Exercise publication boundaries without credentials, uploads or DNS changes."""

import base64
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import publish_maven_central as publisher


DEPLOYMENT = '28570f16-da32-4c14-bd2e-c1acc0782365'


class PublisherTests(unittest.TestCase):
    """Upload admission and server state semantics are independent of BLE tests."""

    def test_missing_token_fails_without_http(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'CENTRAL_TOKEN'):
            publisher.authorization()

    def test_portal_token_pair_uses_bearer(self):
        with patch.dict(os.environ, {'CENTRAL_TOKEN_USERNAME': 'test-user', 'CENTRAL_TOKEN_PASSWORD': 'test-pass'}):
            self.assertEqual(publisher.authorization(), 'Bearer ' + base64.b64encode(b'test-user:test-pass').decode())

    def test_unsigned_bundle_cannot_upload(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, 'release.json').write_text(json.dumps({'upload_ready': False}))
            with patch.object(publisher, 'request') as request, self.assertRaisesRegex(ValueError, 'not signed'):
                publisher.upload(folder)
            request.assert_not_called()

    def test_changed_bundle_cannot_upload(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, 'signed.zip').write_bytes(b'changed')
            Path(folder, 'release.json').write_text(json.dumps({
                'upload_ready': True, 'signing_key': 'test-key', 'bundle': 'signed.zip',
                'bundle_sha256': '0' * 64,
            }))
            with patch.object(publisher, 'request') as request, self.assertRaisesRegex(ValueError, 'changed'):
                publisher.upload(folder)
            request.assert_not_called()

    def test_bundle_filename_cannot_escape_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, 'release.json').write_text(json.dumps({
                'upload_ready': True, 'signing_key': 'test-key', 'bundle': '../signed.zip',
            }))
            with self.assertRaisesRegex(ValueError, 'filename'):
                publisher.checked_bundle(folder)

    def make_signed_inventory(self, folder, *, missing_signature=False, bad_checksum=False):
        """Only the packager verifies real GPG; exercise uploader inventory here."""
        folder = Path(folder)
        coordinate = 'com.evenrealities.devices:even-devices-native-android:0.1.0-beta.11'
        prefix = 'com/evenrealities/devices/even-devices-native-android/0.1.0-beta.11/'
        stem = 'even-devices-native-android-0.1.0-beta.11'
        archive = folder / 'signed.zip'
        with zipfile.ZipFile(archive, 'w') as zipped:
            for suffix in ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar'):
                name = prefix + stem + suffix
                zipped.writestr(name, b'fixture')
                if not (missing_signature and suffix == '.aar'):
                    zipped.writestr(name + '.asc', b'-----BEGIN PGP SIGNATURE-----\nfixture')
                for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                    digest = hashlib.new(algorithm, b'fixture').hexdigest()
                    zipped.writestr(name + '.' + algorithm, 'wrong' if bad_checksum else digest)
        (folder / 'release.json').write_text(json.dumps({
            'upload_ready': True, 'signing_key': 'fixture', 'bundle': archive.name,
            'bundle_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
            'coordinate': coordinate, 'version': '0.1.0-beta.11',
            'artifact_sha256': hashlib.sha256(b'fixture').hexdigest(),
        }))

    def test_signed_inventory_is_admitted(self):
        with tempfile.TemporaryDirectory() as folder:
            self.make_signed_inventory(folder)
            release, archive, data = publisher.checked_bundle(folder)
            self.assertEqual(release['version'], '0.1.0-beta.11')
            self.assertEqual(data, archive.read_bytes())

    def test_published_beta14_is_rejected_before_http(self):
        with tempfile.TemporaryDirectory() as folder:
            self.make_signed_inventory(folder)
            path = Path(folder, 'release.json')
            record = json.loads(path.read_text())
            record['version'] = '0.1.0-beta.14'
            record['coordinate'] = 'com.evenrealities.devices:even-devices-native-android:0.1.0-beta.14'
            path.write_text(json.dumps(record))
            with patch.object(publisher, 'request') as request, self.assertRaisesRegex(ValueError, 'already published'):
                publisher.upload(folder)
            request.assert_not_called()

    def test_missing_signature_is_rejected_before_http(self):
        with tempfile.TemporaryDirectory() as folder:
            self.make_signed_inventory(folder, missing_signature=True)
            with patch.object(publisher, 'request') as request, self.assertRaisesRegex(ValueError, 'inventory'):
                publisher.upload(folder)
            request.assert_not_called()

    def test_invalid_checksum_is_rejected_before_http(self):
        with tempfile.TemporaryDirectory() as folder:
            self.make_signed_inventory(folder, bad_checksum=True)
            with patch.object(publisher, 'request') as request, self.assertRaisesRegex(ValueError, 'checksum'):
                publisher.upload(folder)
            request.assert_not_called()

    def test_upload_defaults_to_validation_and_reports_only_upload(self):
        release = {'coordinate': 'com.evenrealities.devices:even-devices-native-android:0.1.0-beta.11'}
        with patch.object(publisher, 'checked_bundle', return_value=(release, Path('sdk.zip'), b'bytes')), \
                patch.object(publisher, 'request', return_value=DEPLOYMENT.encode()) as request:
            result = publisher.upload('unused')
        self.assertEqual(result['state'], 'UPLOADED')
        self.assertIn('publishingType=USER_MANAGED', request.call_args.args[0])
        self.assertIn(b'name="bundle"', request.call_args.args[1])
        self.assertIn(b'bytes', request.call_args.args[1])

    def test_automatic_upload_is_explicit(self):
        with patch.object(publisher, 'checked_bundle', return_value=({'coordinate': 'sdk'}, Path('sdk.zip'), b'x')), \
                patch.object(publisher, 'request', return_value=DEPLOYMENT.encode()) as request:
            publisher.upload('unused', automatic=True)
        self.assertIn('publishingType=AUTOMATIC', request.call_args.args[0])

    def test_publish_requires_server_validation(self):
        for state in ('PENDING', 'VALIDATING', 'FAILED', 'PUBLISHED'):
            with self.subTest(state=state), patch.object(publisher, 'status', return_value={'state': state}), \
                    patch.object(publisher, 'request') as request, self.assertRaisesRegex(ValueError, 'VALIDATED'):
                publisher.publish(DEPLOYMENT)
            request.assert_not_called()

    def test_validated_publish_is_still_only_requested(self):
        with patch.object(publisher, 'status', return_value={'state': 'VALIDATED'}), \
                patch.object(publisher, 'request', return_value=b'') as request:
            result = publisher.publish(DEPLOYMENT)
        self.assertEqual(result['state'], 'PUBLICATION_REQUESTED')
        self.assertEqual(request.call_args.args[0], '/deployment/' + DEPLOYMENT)

    def test_status_preserves_actual_state(self):
        for state in ('VALIDATED', 'PUBLISHED', 'FAILED'):
            with self.subTest(state=state), patch.object(publisher, 'request', return_value=json.dumps({'deploymentState': state}).encode()):
                self.assertEqual(publisher.status(DEPLOYMENT)['state'], state)

    def test_invalid_deployment_id_fails_without_http(self):
        with patch.object(publisher, 'request') as request, self.assertRaises(ValueError):
            publisher.publish('../another-path')
        request.assert_not_called()

    def test_redirect_does_not_forward_credential(self):
        with self.assertRaises(urllib.error.HTTPError) as raised:
            publisher.NoRedirect().redirect_request(
                type('Request', (), {'full_url': publisher.PORTAL})(),
                io.BytesIO(), 302, 'Found', {}, 'https://other.example/',
            )
        raised.exception.close()


if __name__ == '__main__':
    unittest.main()
