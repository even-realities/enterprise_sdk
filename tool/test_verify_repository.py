"""Exercise release tampering and path confinement with no network or dependencies."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from verify_repository import verify_inventory


class ReleaseInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.payload = self.root / 'artifact.zip'
        self.payload.write_bytes(b'original binary')
        self.manifest = self.root / 'checksums'
        self.line = hashlib.sha256(self.payload.read_bytes()).hexdigest() + '  artifact.zip\n'
        self.manifest.write_text(self.line)

    def test_original_bytes_accepted(self):
        self.assertEqual({'artifact.zip'}, verify_inventory(self.root, self.manifest))

    def test_mutated_binary_rejected(self):
        self.payload.write_bytes(b'rebuilt with same version')
        with self.assertRaises(ValueError): verify_inventory(self.root, self.manifest)

    def test_duplicate_entries_rejected(self):
        self.manifest.write_text(self.line * 2)
        with self.assertRaises(ValueError): verify_inventory(self.root, self.manifest)

    def test_relative_escape_rejected(self):
        self.manifest.write_text('0' * 64 + '  ../artifact.zip\n')
        with self.assertRaises(ValueError): verify_inventory(self.root, self.manifest)

    def test_symbolic_link_rejected(self):
        self.payload.rename(self.root / 'target')
        self.payload.symlink_to(self.root / 'target')
        with self.assertRaises(ValueError): verify_inventory(self.root, self.manifest)

    def test_empty_inventory_rejected(self):
        self.manifest.write_text('')
        with self.assertRaises(ValueError): verify_inventory(self.root, self.manifest)


if __name__ == '__main__':
    unittest.main()
