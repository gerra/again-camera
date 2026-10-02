#!/usr/bin/env python3
"""Tests for tools/play.py: the parts of a Play upload that need no Google and no Android SDK.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

The service account JWT, the four API calls and their order, the Gradle arguments and what
the workflow passes in are checked with fakes; the one openssl call is exercised with a
throwaway key, since a wrong signature would only show up as a 401 from Google.
"""

import base64
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import play

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/play.yml"
ANDROID_WORKFLOW = ROOT / ".github/workflows/android.yml"
IOS_WORKFLOW = ROOT / ".github/workflows/ios.yml"


def trigger_paths(workflow_text):
    """The `paths:` lists of a workflow's triggers, in order, without a YAML parser."""
    lists, current = [], None
    for line in workflow_text.splitlines():
        if line.strip() == "paths:":
            current = []
            lists.append(current)
        elif current is not None and line.startswith("      - "):
            current.append(line.strip()[2:].strip('"'))
        elif current is not None and not line.startswith("      #"):
            current = None
    return lists


def decode(segment):
    """A JWT segment back into its JSON."""
    padded = segment + "=" * (-len(segment) % 4)
    return json.loads(base64.urlsafe_b64decode(padded))


ACCOUNT = {
    "client_email": "play@again.iam.gserviceaccount.com",
    "token_uri": "https://oauth2.googleapis.com/token",
    "private_key": "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n",
}


class Recorder:
    """A fake `http` that remembers every call and answers from a script."""

    def __init__(self, answers):
        self.answers = list(answers)
        self.calls = []

    def __call__(self, method, url, body=None, content_type="application/json", token=None):
        self.calls.append((method, url, body, content_type, token))
        return self.answers.pop(0)


class SettingsTest(unittest.TestCase):
    def test_the_application_id_is_read_out_of_the_app_build_file(self):
        self.assertEqual("sh.gerra.again", play.application_id(play.ANDROID_GRADLE.read_text()))
        self.assertEqual("sh.gerra.again", play.application_id('        applicationId = "sh.gerra.again"\n'))
        self.assertEqual("sh.gerra.again", play.application_id('    applicationId "sh.gerra.again"\n'))

    def test_a_gradle_file_without_one_stops_the_step(self):
        with self.assertRaises(SystemExit):
            play.application_id('namespace = "sh.gerra.again"\n')

    def test_the_version_name_is_read_out_of_the_app_build_file(self):
        self.assertRegex(play.version_name(play.ANDROID_GRADLE.read_text()), r"^\d+\.\d+$")
        self.assertEqual("1.2", play.version_name('        versionName = "1.2"\n'))
        with self.assertRaises(SystemExit):
            play.version_name('        versionCode = 1\n')

    def test_the_bundle_is_where_the_android_gradle_plugin_writes_it(self):
        self.assertEqual(
            "composeApp/build/outputs/bundle/release/composeApp-release.aab",
            str(play.BUNDLE.relative_to(ROOT)),
        )

    def test_the_mapping_is_where_r8_writes_it(self):
        self.assertEqual(
            "composeApp/build/outputs/mapping/release/mapping.txt",
            str(play.MAPPING.relative_to(ROOT)),
        )

    def test_the_release_build_is_shrunk_so_there_is_a_mapping_to_send(self):
        release = play.ANDROID_GRADLE.read_text().split("release {", 1)[1]
        self.assertRegex(release, r"minifyEnabled = true")
        self.assertRegex(release, r"shrinkResources = true")


class AssertionTest(unittest.TestCase):
    def test_the_jwt_carries_the_account_scope_and_an_hour(self):
        header, claims = (decode(part) for part in play.assertion(ACCOUNT, 1_700_000_000).decode().split("."))
        self.assertEqual({"alg": "RS256", "typ": "JWT"}, header)
        self.assertEqual(
            {
                "iss": "play@again.iam.gserviceaccount.com",
                "scope": "https://www.googleapis.com/auth/androidpublisher",
                "aud": "https://oauth2.googleapis.com/token",
                "iat": 1_700_000_000,
                "exp": 1_700_003_600,
            },
            claims,
        )

    def test_segments_are_unpadded_base64url(self):
        for segment in play.assertion(ACCOUNT, 1).split(b"."):
            self.assertNotIn(b"=", segment)
            self.assertNotIn(b"+", segment)
            self.assertNotIn(b"/", segment)
        self.assertEqual("-_8", play.base64url(b"\xfb\xff"))

    def test_the_token_request_carries_the_signed_jwt_and_the_key_is_gone_after(self):
        with tempfile.TemporaryDirectory() as temp:
            os.environ["RUNNER_TEMP"] = temp
            try:
                account = pathlib.Path(temp) / "account.json"
                account.write_text(json.dumps(ACCOUNT))
                signed_over = []

                def sign(key_path, data):
                    self.assertEqual(ACCOUNT["private_key"], pathlib.Path(key_path).read_text())
                    signed_over.append(data)
                    return b"\x01\x02\x03"

                request = Recorder([{"access_token": "ya29.token"}])
                token = play.access_token(account, sign=sign, request=request, now=1_700_000_000)
            finally:
                del os.environ["RUNNER_TEMP"]

        self.assertEqual("ya29.token", token)
        self.assertFalse((pathlib.Path(temp) / "play-service-account-key.pem").exists())
        [(method, url, body, content_type, bearer)] = request.calls
        self.assertEqual(("POST", ACCOUNT["token_uri"], "application/x-www-form-urlencoded", None), (method, url, content_type, bearer))
        form = dict(pair.split("=", 1) for pair in body.decode().split("&"))
        self.assertEqual("urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer", form["grant_type"])
        unsigned, signature = form["assertion"].rsplit(".", 1)
        self.assertEqual(signed_over, [unsigned.encode()])
        self.assertEqual("AQID", signature)

    def test_openssl_signs_the_way_google_checks(self):
        """RS256 is RSA PKCS#1 v1.5 over SHA-256: the same openssl verifies what it signed."""
        with tempfile.TemporaryDirectory() as temp:
            os.environ["RUNNER_TEMP"] = temp
            try:
                key = pathlib.Path(temp) / "key.pem"
                public = pathlib.Path(temp) / "public.pem"
                subprocess.run(
                    ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", key],
                    check=True, capture_output=True,
                )
                subprocess.run(["openssl", "pkey", "-in", key, "-pubout", "-out", public], check=True, capture_output=True)
                data = play.assertion(ACCOUNT, 1_700_000_000)
                signature = play.sign_with_openssl(key, data)
                signature_file = pathlib.Path(temp) / "sig"
                signature_file.write_bytes(signature)
                data_file = pathlib.Path(temp) / "data"
                data_file.write_bytes(data)
                verified = subprocess.run(
                    ["openssl", "dgst", "-sha256", "-verify", public, "-signature", signature_file, data_file],
                    capture_output=True, text=True,
                )
                leftovers = list(pathlib.Path(temp).glob("play-jwt*"))
            finally:
                del os.environ["RUNNER_TEMP"]
        self.assertEqual(0, verified.returncode, verified.stdout + verified.stderr)
        self.assertEqual(256, len(signature))
        self.assertEqual([], leftovers)


class PlayApiTest(unittest.TestCase):
    def test_a_release_is_an_edit_a_bundle_its_mapping_a_track_and_a_commit(self):
        with tempfile.TemporaryDirectory() as temp:
            bundle = pathlib.Path(temp) / "app.aab"
            bundle.write_bytes(b"PK\x03\x04bundle")
            mapping = pathlib.Path(temp) / "mapping.txt"
            mapping.write_bytes(b"sh.gerra.again.MainActivity -> sh.gerra.again.MainActivity:\n")
            request = Recorder([
                {"id": "edit-1"},
                {"versionCode": 57, "sha256": "x"},
                {"deobfuscationFile": {"symbolType": "proguard"}},
                {"track": "internal"},
                {"id": "edit-1"},
            ])
            api = play.PlayApi("ya29.token", "sh.gerra.again", request=request)
            self.assertEqual(57, api.release(bundle, mapping, "internal", "1.0"))

        base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/sh.gerra.again"
        methods_and_urls = [(method, url) for method, url, *_ in request.calls]
        self.assertEqual(
            [
                ("POST", f"{base}/edits"),
                ("POST", "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/sh.gerra.again/edits/edit-1/bundles?uploadType=media"),
                # The mapping goes under the versionCode Play read out of the bundle.
                ("POST", "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/sh.gerra.again/edits/edit-1/deobfuscationFiles/57/proguard?uploadType=media"),
                ("PUT", f"{base}/edits/edit-1/tracks/internal"),
                ("POST", f"{base}/edits/edit-1:commit"),
            ],
            methods_and_urls,
        )
        self.assertEqual({"ya29.token"}, {token for *_, token in request.calls})
        _, _, body, content_type, _ = request.calls[1]
        self.assertEqual(("application/octet-stream", b"PK\x03\x04bundle"), (content_type, body))
        _, _, body, content_type, _ = request.calls[2]
        self.assertEqual(
            ("application/octet-stream", b"sh.gerra.again.MainActivity -> sh.gerra.again.MainActivity:\n"),
            (content_type, body),
        )
        _, _, body, content_type, _ = request.calls[3]
        self.assertEqual("application/json", content_type)
        self.assertEqual(
            {"track": "internal", "releases": [{"name": "1.0 (57)", "versionCodes": ["57"], "status": "completed"}]},
            json.loads(body),
        )

    def test_the_release_name_pairs_the_version_with_the_build(self):
        body = play.release_body("alpha", 12, "2.0")
        self.assertEqual("2.0 (12)", body["releases"][0]["name"])
        self.assertEqual(["12"], body["releases"][0]["versionCodes"])
        self.assertEqual("completed", body["releases"][0]["status"])


class GradlePropertiesTest(unittest.TestCase):
    ENVIRONMENT = {
        "BUILD_NUMBER": "57",
        "KEYSTORE_PATH": "/tmp/upload.jks",
        "ANDROID_UPLOAD_KEYSTORE_PASSWORD": "store-pw",
        "ANDROID_UPLOAD_KEY_ALIAS": "upload",
        "ANDROID_UPLOAD_KEY_PASSWORD": "key-pw",
    }

    def test_the_build_number_and_the_upload_key_reach_gradle(self):
        self.assertEqual(
            [
                "-Pagain.versionCode=57",
                "-Pagain.uploadKeystore=/tmp/upload.jks",
                "-Pagain.uploadKeystorePassword=store-pw",
                "-Pagain.uploadKeyAlias=upload",
                "-Pagain.uploadKeyPassword=key-pw",
            ],
            play.gradle_properties(self.ENVIRONMENT),
        )

    def test_the_gradle_file_reads_what_is_passed(self):
        """Each property the script passes is one the app's build file looks up, and the other way round."""
        gradle = play.ANDROID_GRADLE.read_text()
        looked_up = set(re.findall(r'findProperty\([\'"](again\.[A-Za-z]+)[\'"]\)', gradle))
        passed = {p[2:].split("=", 1)[0] for p in play.gradle_properties(self.ENVIRONMENT)}
        self.assertEqual(set(), passed - looked_up, "passed to Gradle but not read by the app's build file")
        self.assertEqual(set(), looked_up - passed, "read by the app's build file but not passed")


class CheckSecretsTest(unittest.TestCase):
    def test_a_missing_secret_skips_rather_than_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            output = pathlib.Path(temp) / "output"
            environment = dict.fromkeys(play.SECRETS, "x")
            del environment["PLAY_SERVICE_ACCOUNT_JSON"]
            saved = {name: os.environ.pop(name, None) for name in play.SECRETS + ["GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY"]}
            os.environ.update(environment)
            os.environ["GITHUB_OUTPUT"] = str(output)
            try:
                play.check_secrets(None)
                self.assertEqual("configured=false\n", output.read_text())
                os.environ["PLAY_SERVICE_ACCOUNT_JSON"] = "{}"
                play.check_secrets(None)
                self.assertEqual("configured=false\nconfigured=true\n", output.read_text())
            finally:
                for name in play.SECRETS + ["GITHUB_OUTPUT"]:
                    os.environ.pop(name, None)
                os.environ.update({name: value for name, value in saved.items() if value is not None})


class WorkflowTest(unittest.TestCase):
    """The workflow file hands the script what it expects, and the Android one runs these tests."""

    def test_the_workflow_passes_every_secret_the_script_checks(self):
        workflow = WORKFLOW.read_text()
        for name in play.SECRETS:
            self.assertIn(f"{name}: ${{{{ secrets.{name} }}}}", workflow)

    def test_the_workflow_runs_every_command_in_order(self):
        workflow = WORKFLOW.read_text()
        positions = [workflow.index(f"python3 tools/play.py {command}") for command in play.COMMANDS]
        self.assertEqual(sorted(positions), positions)

    def test_the_run_keeps_the_mapping_next_to_the_bundle(self):
        workflow = WORKFLOW.read_text()
        keep = workflow[workflow.index("name: Keep the bundle"):]
        self.assertIn("${{ env.BUNDLE_PATH }}", keep)
        self.assertIn("${{ env.MAPPING_PATH }}", keep)

    def test_the_android_workflow_runs_these_tests(self):
        workflow = ANDROID_WORKFLOW.read_text()
        self.assertIn("python3 -m unittest discover -s tools -p 'test_*.py'", workflow)
        for path in ("tools/**", ".github/workflows/play.yml"):
            self.assertIn(f'- "{path}"', workflow)

    def test_the_build_workflows_watch_the_same_paths_on_main_and_on_pull_requests(self):
        for workflow in (ANDROID_WORKFLOW, IOS_WORKFLOW):
            with self.subTest(workflow=workflow.name):
                text = workflow.read_text()
                self.assertIn("branches: [main]", text)
                self.assertIn("pull_request:", text)
                on_push, on_pull_request = trigger_paths(text)
                self.assertTrue(on_push)
                self.assertEqual(on_push, on_pull_request)
                self.assertIn(f".github/workflows/{workflow.name}", on_push)

    def test_the_play_workflow_only_runs_by_hand(self):
        triggers = WORKFLOW.read_text().split("jobs:", 1)[0]
        self.assertIn("workflow_dispatch:", triggers)
        for event in ("push:", "pull_request:", "schedule:"):
            self.assertNotIn(event, triggers)


if __name__ == "__main__":
    unittest.main()
