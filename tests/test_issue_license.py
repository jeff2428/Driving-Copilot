import base64
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.issue_license import issue, openssl


class LicenseIssuerTest(unittest.TestCase):
    def test_signed_payload_is_verifiable(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            key = directory / "test.pem"
            public = directory / "test.pub"
            signature = directory / "test.sig"
            exe = openssl()
            subprocess.run([exe, "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(key)], check=True)
            subprocess.run([exe, "pkey", "-in", str(key), "-pubout", "-out", str(public)], check=True)
            device = "RG-1234-5678-9ABC-DEF0"
            prefix, target, expiry, encoded = issue(key, device, None).split(".")
            self.assertEqual((prefix, target, expiry), ("PRO2", device, "99991231"))
            signature.write_bytes(base64.urlsafe_b64decode(encoded + "=="))
            subprocess.run([exe, "dgst", "-sha256", "-verify", str(public), "-signature", str(signature)],
                           input=f"{device}|{expiry}".encode(), check=True, capture_output=True)
