#!/usr/bin/env python3
"""Signing, bundling and uploading the Android app to Google Play, for .github/workflows/play.yml.

One command per step of that workflow, in the order it runs them, the way gerra/gains ships:

  check-secrets    Say whether the Play secrets are all set. A missing one is not an error here:
                   the step's `configured` output turns the rest of the job off and the run
                   summary names it, so the workflow can exist before the Play Console does.
  paths            Where the upload key, the service account key and the bundle live on this runner.
  settings         Read the application id and the version name out of composeApp/android.gradle,
                   and the track out of the PLAY_TRACK repository variable.
  install-signing  Write the upload keystore and the service account key out of the secrets.
  bundle           `gradle bundleRelease`, signed with the upload key and stamped with the
                   build number as versionCode. R8 writes the mapping file next to it.
  upload           Send the bundle and its mapping file to the track through the Play
                   Developer API.
  cleanup          Remove the key material from the runner.

The Play Developer API is called with the standard library and `openssl`, which signs the
service account's JWT: no client library to install on the runner. One-time setup and the
list of secrets: docs/android.md
"""

import argparse
import base64
import json
import os
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request

import gha

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The Android side of the app module: the application id, the version and the signing are in
# it, and the bundle is its output.
ANDROID_GRADLE = ROOT / "composeApp/android.gradle"
BUNDLE = ROOT / "composeApp/build/outputs/bundle/release/composeApp-release.aab"
# R8's map from the obfuscated names back to the real ones. Play keeps it with the bundle's
# versionCode and uses it to make Play Console's stack traces read.
MAPPING = ROOT / "composeApp/build/outputs/mapping/release/mapping.txt"

SECRETS = [
    "ANDROID_UPLOAD_KEYSTORE_BASE64",
    "ANDROID_UPLOAD_KEYSTORE_PASSWORD",
    "ANDROID_UPLOAD_KEY_ALIAS",
    "ANDROID_UPLOAD_KEY_PASSWORD",
    "PLAY_SERVICE_ACCOUNT_JSON",
]

# The track a release goes to when the PLAY_TRACK variable is unset: internal testing, which
# needs no review and reaches its testers at once. Play's first closed testing track is "alpha"
# in the API, whatever the console shows; a track created by hand goes by its own name.
DEFAULT_TRACK = "internal"

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def keystore_path():
    return gha.temp("upload.jks")


def service_account_path():
    return gha.temp("play-service-account.json")


def application_id(gradle_text):
    """The applicationId in the app's build file: what Play knows the app as."""
    found = re.search(r'^\s*applicationId\s*=?\s*"([^"]+)"', gradle_text, flags=re.M)
    if not found:
        gha.fail(f"applicationId is not set in {ANDROID_GRADLE.relative_to(ROOT)}")
    return found.group(1)


def version_name(gradle_text):
    """The versionName in the app's build file: the version people see on Play."""
    found = re.search(r'^\s*versionName\s*=?\s*"([^"]+)"', gradle_text, flags=re.M)
    if not found:
        gha.fail(f"versionName is not set in {ANDROID_GRADLE.relative_to(ROOT)}")
    return found.group(1)


def decode_secret(name, destination):
    """Write a base64 secret out as a file, readable only by this user."""
    destination.write_bytes(base64.b64decode(gha.secret(name)))
    destination.chmod(0o600)
    return destination


# --- the Play Developer API -------------------------------------------------


def base64url(data):
    """The unpadded base64 JWTs are made of."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def assertion(account, now):
    """The unsigned part of the service account's JWT: `header.claims`, as bytes to sign.

    Google trades this, signed with the account's private key, for an access token that is
    good for an hour. `account` is the parsed service account JSON file.
    """
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": account["client_email"],
        "scope": SCOPE,
        "aud": account["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }
    return ".".join(
        base64url(json.dumps(part, separators=(",", ":")).encode()) for part in (header, claims)
    ).encode()


def sign_with_openssl(key_pem_path, data):
    """RS256 over `data` with the service account's private key, via the openssl on the runner.

    Through files rather than pipes: gha.run speaks text, and a signature is bytes.
    """
    unsigned, signature = gha.temp("play-jwt.txt"), gha.temp("play-jwt.sig")
    unsigned.write_bytes(data)
    try:
        gha.run(
            "openssl", "dgst", "-sha256", "-sign", key_pem_path, "-out", signature, unsigned,
            show=False,
        )
        return signature.read_bytes()
    finally:
        unsigned.unlink(missing_ok=True)
        signature.unlink(missing_ok=True)


def http(method, url, body=None, content_type="application/json", token=None, timeout=600):
    """One request to Google, answered as parsed JSON. An error names the API's own message."""
    headers = {}
    if body is not None:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        gha.fail(f"{method} {url} answered {error.code}: {detail}")
    return json.loads(text) if text else {}


def access_token(account_path, sign=sign_with_openssl, request=http, now=None):
    """An access token for the Play Developer API, from the service account key file."""
    account = json.loads(account_path.read_text())
    key = gha.temp("play-service-account-key.pem")
    key.write_text(account["private_key"])
    key.chmod(0o600)
    try:
        unsigned = assertion(account, int(now if now is not None else time.time()))
        jwt = unsigned + b"." + base64url(sign(key, unsigned)).encode()
    finally:
        key.unlink(missing_ok=True)
    answer = request(
        "POST", account["token_uri"],
        body=urllib.parse.urlencode({
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": jwt.decode(),
        }).encode(),
        content_type="application/x-www-form-urlencoded",
    )
    return answer["access_token"]


def release_body(track, version_code, version_name):
    """What the track update says: one completed release carrying this bundle."""
    return {
        "track": track,
        "releases": [
            {
                "name": f"{version_name} ({version_code})",
                "versionCodes": [str(version_code)],
                "status": "completed",
            }
        ],
    }


class PlayApi:
    """The calls an upload takes, in order: an edit is opened, the bundle goes into it with its
    mapping file, the track is pointed at the bundle, and the edit is committed, which is when
    Play acts.

    `request` is `http` on the runner and a fake in the tests, so nothing here talks to Google
    unless asked to.
    """

    def __init__(self, token, package, request=http):
        self.token = token
        self.package = package
        self.request = request

    def call(self, method, url, body=None, content_type="application/json"):
        return self.request(method, url, body=body, content_type=content_type, token=self.token)

    def open_edit(self):
        return self.call("POST", f"{API}/{self.package}/edits", body=b"{}")["id"]

    def upload_bundle(self, edit, bundle):
        answer = self.call(
            "POST",
            f"{UPLOAD_API}/{self.package}/edits/{edit}/bundles?uploadType=media",
            body=bundle.read_bytes(),
            content_type="application/octet-stream",
        )
        return answer["versionCode"]

    def upload_mapping(self, edit, version_code, mapping):
        """R8's mapping.txt, as the deobfuscation file of the bundle with this versionCode."""
        self.call(
            "POST",
            f"{UPLOAD_API}/{self.package}/edits/{edit}/deobfuscationFiles/{version_code}/proguard"
            "?uploadType=media",
            body=mapping.read_bytes(),
            content_type="application/octet-stream",
        )

    def set_track(self, edit, track, version_code, version_name):
        self.call(
            "PUT",
            f"{API}/{self.package}/edits/{edit}/tracks/{track}",
            body=json.dumps(release_body(track, version_code, version_name)).encode(),
        )

    def commit(self, edit):
        self.call("POST", f"{API}/{self.package}/edits/{edit}:commit", body=b"")

    def release(self, bundle, mapping, track, version_name):
        """The whole upload. Returns the versionCode Play read out of the bundle."""
        edit = self.open_edit()
        version_code = self.upload_bundle(edit, bundle)
        self.upload_mapping(edit, version_code, mapping)
        self.set_track(edit, track, version_code, version_name)
        self.commit(edit)
        return version_code


# --- the steps --------------------------------------------------------------


def paths(args):
    """The runner context is not available in job-level env, so the paths are set here."""
    gha.export(
        KEYSTORE_PATH=keystore_path(),
        SERVICE_ACCOUNT_PATH=service_account_path(),
        BUNDLE_PATH=BUNDLE,
        MAPPING_PATH=MAPPING,
    )


def check_secrets(args):
    """`configured=true` when every Play secret is set; otherwise `false`, and the job skips.

    Not a failure: the workflow is in the repository before the Play Console is set up, and a
    run started by hand then should say what is missing rather than go red.
    """
    missing = [name for name in SECRETS if not os.environ.get(name)]
    if missing:
        gha.summary(
            "Play upload skipped: repository secret(s) "
            + ", ".join(f"`{name}`" for name in missing)
            + " not set (see docs/android.md)."
        )
        gha.output(configured="false")
        return
    print(f"All {len(SECRETS)} Play secrets are set.")
    gha.output(configured="true")


def settings(args):
    gradle = ANDROID_GRADLE.read_text()
    gha.export(
        APPLICATION_ID=application_id(gradle),
        VERSION_NAME=version_name(gradle),
        PLAY_TRACK=os.environ.get("PLAY_TRACK") or DEFAULT_TRACK,
    )
    gha.run("java", "-version")


def install_signing(args):
    decode_secret("ANDROID_UPLOAD_KEYSTORE_BASE64", keystore_path())
    account = service_account_path()
    account.write_text(gha.secret("PLAY_SERVICE_ACCOUNT_JSON"))
    account.chmod(0o600)
    try:
        email = json.loads(account.read_text())["client_email"]
    except (ValueError, KeyError):
        gha.fail("PLAY_SERVICE_ACCOUNT_JSON is not a service account key file (see docs/android.md)")
    print(f"Installed the upload key and the service account key for {email}")


def gradle_properties(environment):
    """The -P arguments `bundle` passes: the version code and the upload key."""
    properties = {
        "again.versionCode": environment["BUILD_NUMBER"],
        "again.uploadKeystore": environment["KEYSTORE_PATH"],
        "again.uploadKeystorePassword": environment["ANDROID_UPLOAD_KEYSTORE_PASSWORD"],
        "again.uploadKeyAlias": environment["ANDROID_UPLOAD_KEY_ALIAS"],
        "again.uploadKeyPassword": environment["ANDROID_UPLOAD_KEY_PASSWORD"],
    }
    return [f"-P{name}={value}" for name, value in properties.items()]


def bundle(args):
    build = os.environ["BUILD_NUMBER"]
    print(
        f"Bundling Again {os.environ['VERSION_NAME']} ({build}) for "
        f"{os.environ['APPLICATION_ID']}"
    )
    # Not echoed: the key passwords travel on the command line.
    gha.run(
        str(ROOT / "gradlew"), ":composeApp:bundleRelease", "--no-daemon",
        *gradle_properties(os.environ),
        cwd=ROOT, show=False,
    )
    if not BUNDLE.exists():
        gha.fail(f"bundleRelease left no bundle at {BUNDLE.relative_to(ROOT)}")
    # R8 is on for release builds, so a bundle without a mapping means the build changed under
    # us; Play would show obfuscated stack traces for this version for good.
    if not MAPPING.exists():
        gha.fail(f"bundleRelease left no mapping file at {MAPPING.relative_to(ROOT)}")
    print(f"Built {BUNDLE.relative_to(ROOT)} ({BUNDLE.stat().st_size // 1024} KB)")


def upload(args):
    version = os.environ["VERSION_NAME"]
    track = os.environ["PLAY_TRACK"]
    token = access_token(service_account_path())
    api = PlayApi(token, os.environ["APPLICATION_ID"])
    version_code = api.release(BUNDLE, MAPPING, track, version)
    gha.summary(
        f"Uploaded **Again {version} ({version_code})** to the Play `{track}` track. "
        "Testers on it get the build once Play has processed it.",
        echo=False,
    )


def cleanup(args):
    """Leave nothing behind, whether or not the upload worked."""
    keystore_path().unlink(missing_ok=True)
    service_account_path().unlink(missing_ok=True)


COMMANDS = {
    "check-secrets": check_secrets,
    "paths": paths,
    "settings": settings,
    "install-signing": install_signing,
    "bundle": bundle,
    "upload": upload,
    "cleanup": cleanup,
}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=list(COMMANDS))
    args = parser.parse_args(argv)
    COMMANDS[args.command](args)


if __name__ == "__main__":
    main()
