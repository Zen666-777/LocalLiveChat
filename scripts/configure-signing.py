"""Provision MEL Chat's fixed signing key without printing credentials or key bytes.

Uses the repository's normal Git Credential Manager authentication. Repository
secrets are sealed with GitHub's public key before they leave this process.
Private backups are kept in the ignored .tools/signing directory only.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
REPO = "Zen666-777/LocalLiveChat"
SECRET = "MEL_DEBUG_KEYSTORE_BASE64"
PRIVATE = ROOT / ".tools" / "signing"
KEYSTORE = PRIVATE / "mel-chat-debug.keystore"
BRANCH = "codex/mel-chat-mvp"


class SigningError(Exception):
    pass


def git_credentials() -> str:
    environment = os.environ.copy()
    environment["GIT_TERMINAL_PROMPT"] = "0"
    environment["GCM_INTERACTIVE"] = "never"
    result = subprocess.run(
        ["git", "credential", "fill"],
        input=f"protocol=https\nhost=github.com\nusername=Zen666-777\npath={REPO}.git\n\n",
        text=True, capture_output=True, cwd=ROOT, env=environment, timeout=60,
    )
    if result.returncode:
        raise SigningError("Existing GitHub authentication could not be used.")
    fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    token = fields.get("password")
    if not token:
        raise SigningError("GitHub authentication did not supply a credential.")
    return token


class GitHub:
    def __init__(self):
        self._token = git_credentials()

    def request(self, method: str, suffix: str, data=None, allow_missing=False):
        if not suffix.startswith("/") or ".." in suffix or "://" in suffix:
            raise SigningError("Invalid repository API path.")
        request = Request(
            f"https://api.github.com/repos/{REPO}{suffix}",
            data=None if data is None else json.dumps(data).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self._token}",
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2026-03-10",
                "User-Agent": "MEL-Chat-Signing-Setup",
            }, method=method,
        )
        try:
            with urlopen(request, timeout=45) as response:
                payload = response.read()
                return json.loads(payload) if payload else {}
        except HTTPError as error:
            if error.code == 404 and allow_missing:
                return None
            # Never echo request headers, response dumps, or credentials.
            raise SigningError(f"GitHub API rejected {method} {suffix} (HTTP {error.code}).") from None


def certificate_digest() -> str:
    keytools = sorted((ROOT / ".tools" / "jdk").glob("*/bin/keytool.exe"))
    if not keytools:
        raise SigningError("Local JDK keytool is unavailable.")
    result = subprocess.run([
        str(keytools[0]), "-exportcert", "-rfc", "-keystore", str(KEYSTORE),
        "-storepass", "android", "-alias", "androiddebugkey",
    ], capture_output=True, timeout=30)
    if result.returncode:
        raise SigningError("Unable to validate the local signing key.")
    certificate = ssl.PEM_cert_to_DER_cert(result.stdout.decode("ascii"))
    return hashlib.sha256(certificate).hexdigest()


def provision(api: GitHub):
    existing = api.request("GET", f"/actions/secrets/{SECRET}", allow_missing=True)
    if existing:
        # Never rotate a pre-existing key: that would break installed updates.
        return {"secret_exists": True, "changed": False, "updated_at": existing["updated_at"],
                "local_certificate_sha256": certificate_digest() if KEYSTORE.exists() else None}

    api_key = api.request("GET", "/actions/secrets/public-key")
    sys.path.insert(0, str(ROOT / ".tools" / "signing-python"))
    from nacl.public import PublicKey, SealedBox

    PRIVATE.mkdir(parents=True, exist_ok=True)
    ignored = subprocess.run(["git", "check-ignore", "--quiet", str(KEYSTORE.relative_to(ROOT))], cwd=ROOT)
    if ignored.returncode:
        raise SigningError("Private signing directory is not ignored by Git.")
    if not KEYSTORE.exists():
        keytool = next((ROOT / ".tools" / "jdk").glob("*/bin/keytool.exe"), None)
        if not keytool:
            raise SigningError("Local JDK keytool is unavailable.")
        generated = subprocess.run([
            str(keytool), "-genkeypair", "-storetype", "JKS", "-keystore", str(KEYSTORE),
            "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=MEL Chat Personal,O=Personal,C=JP", "-noprompt",
        ], capture_output=True, timeout=60)
        if generated.returncode:
            raise SigningError("Signing key generation failed.")
    digest = certificate_digest()
    public_key = PublicKey(base64.b64decode(api_key["key"]))
    encrypted = SealedBox(public_key).encrypt(base64.b64encode(KEYSTORE.read_bytes()))
    # Recheck immediately before registering to avoid overwriting someone else's setup.
    if api.request("GET", f"/actions/secrets/{SECRET}", allow_missing=True):
        raise SigningError("A signing secret was registered concurrently; it was not overwritten.")
    api.request("PUT", f"/actions/secrets/{SECRET}", {
        "encrypted_value": base64.b64encode(encrypted).decode("ascii"), "key_id": api_key["key_id"],
    })
    registered = api.request("GET", f"/actions/secrets/{SECRET}")
    state = {"repository": REPO, "secret_name": SECRET, "certificate_sha256": digest,
             "registered_at": registered["updated_at"], "private_backup": str(KEYSTORE.relative_to(ROOT))}
    (PRIVATE / "state.json").write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    return {"secret_exists": True, "changed": True, "certificate_sha256": digest,
            "updated_at": registered["updated_at"], "private_backup_saved": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["inspect", "provision", "dispatch"])
    arguments = parser.parse_args()
    api = GitHub()
    if arguments.command == "inspect":
        secret = api.request("GET", f"/actions/secrets/{SECRET}", allow_missing=True)
        api.request("GET", "/actions/secrets/public-key")
        print(json.dumps({"secret_exists": secret is not None, "updated_at": secret.get("updated_at") if secret else None,
                          "local_backup_exists": KEYSTORE.exists(), "repository_access": True}))
    elif arguments.command == "provision":
        print(json.dumps(provision(api)))
    else:
        api.request("POST", "/actions/workflows/android.yml/dispatches", {"ref": BRANCH})
        print(json.dumps({"dispatched": True, "branch": BRANCH}))


if __name__ == "__main__":
    try:
        main()
    except SigningError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # Secret material must never be exposed by an exception representation.
        print(f"Signing setup failed ({type(error).__name__}). No secret data was logged.", file=sys.stderr)
        sys.exit(1)
