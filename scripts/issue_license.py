"""Issue a device-bound RoadGuard license using an operator's external P-256 key."""

import argparse
import base64
import re
import shutil
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path


def openssl() -> str:
    binary = shutil.which("openssl")
    if binary:
        return binary
    git_openssl = Path("C:/Program Files/Git/usr/bin/openssl.exe")
    if git_openssl.is_file():
        return str(git_openssl)
    raise ValueError("OpenSSL is required")


def issue(key: Path, device: str, days: int | None) -> str:
    key = key.resolve(strict=True)
    if key.is_relative_to(Path(__file__).resolve().parents[1]):
        raise ValueError("Store the signing key outside the repository")
    device = device.strip().upper()
    if not re.fullmatch(r"RG(?:-[A-F0-9]{4}){4}", device):
        raise ValueError("Invalid device code")
    if days is not None and days <= 0:
        raise ValueError("Days must be positive")
    today = datetime.now(timezone(timedelta(hours=8))).date()
    expiry = "99991231" if days is None else (today + timedelta(days=days - 1)).strftime("%Y%m%d")
    exe = openssl()
    public = subprocess.check_output([exe, "pkey", "-in", str(key), "-pubout"])
    params = subprocess.check_output([exe, "ec", "-pubin", "-text", "-noout"], input=public)
    if b"ASN1 OID: prime256v1" not in params:
        raise ValueError("Expected an ECDSA P-256 key")
    signature = subprocess.check_output([exe, "dgst", "-sha256", "-sign", str(key)], input=f"{device}|{expiry}".encode())
    return f"PRO2.{device}.{expiry}." + base64.urlsafe_b64encode(signature).decode().rstrip("=")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--device", required=True)
    duration = parser.add_mutually_exclusive_group(required=True)
    duration.add_argument("--days", type=int)
    duration.add_argument("--lifetime", action="store_true")
    args = parser.parse_args()
    try:
        print(issue(args.key, args.device, args.days))
    except (OSError, ValueError, OverflowError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
