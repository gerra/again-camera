#!/usr/bin/env python3
"""Tests for tools/deploy_site.py and the site it ships: the parts that need no box.

Run from the repository root:  python3 -m unittest discover -s tools -p 'test_*.py'

Where the deploy connects and how it rsyncs, which certificate the vhost needs, that the job
skips rather than fails without its secrets, that every page's local links resolve the way
nginx's try_files does, and that the privacy policy the app links to is a page the site serves.
"""

import html.parser
import os
import pathlib
import re
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import deploy_site

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/deploy-site.yml"
ANDROID_WORKFLOW = ROOT / ".github/workflows/android.yml"
LINKS = ROOT / "composeApp/src/commonMain/kotlin/sh/gerra/again/Links.kt"
CONTACT = "mailto:again@gerra.sh"


class RemoteTest(unittest.TestCase):
    def test_the_workflow_connects_with_the_deploy_secrets(self):
        with tempfile.TemporaryDirectory() as temp:
            remote = deploy_site.Remote.from_environment({
                "DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY", "RUNNER_TEMP": temp,
            })
            self.assertEqual("root@box.example", remote.target)
            key = pathlib.Path(temp) / "deploy_key"
            self.assertEqual("KEY\n", key.read_text())
            self.assertEqual(0o600, key.stat().st_mode & 0o777)
            self.assertEqual(["-i", str(key), "-o", "StrictHostKeyChecking=no", "-o", "IdentitiesOnly=yes"], remote.options())

    def test_a_laptop_connects_through_its_host_alias(self):
        self.assertEqual("hetzner_gb", deploy_site.Remote.from_environment({}).target)
        remote = deploy_site.Remote.from_environment({"REMOTE": "elsewhere"})
        self.assertEqual("elsewhere", remote.target)
        self.assertEqual([], remote.options())

    def test_an_incomplete_set_of_secrets_is_not_a_box(self):
        remote = deploy_site.Remote.from_environment({"DEPLOY_HOST": "box.example", "DEPLOY_USER": "root"})
        self.assertEqual("hetzner_gb", remote.target)


class CheckSecretsTest(unittest.TestCase):
    def check(self, env):
        with tempfile.TemporaryDirectory() as temp:
            output = pathlib.Path(temp) / "output"
            summary = pathlib.Path(temp) / "summary"
            env = {**env, "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}
            with unittest.mock.patch.dict(os.environ, env, clear=True):
                deploy_site.check_secrets(None)
            return output.read_text(), summary.read_text() if summary.exists() else ""

    def test_a_missing_secret_skips_rather_than_fails(self):
        output, summary = self.check({"DEPLOY_HOST": "box.example", "DEPLOY_USER": "root"})
        self.assertEqual("configured=false\n", output)
        self.assertIn("`DEPLOY_KEY`", summary)
        self.assertNotIn("`DEPLOY_HOST`", summary)

    def test_every_secret_set_deploys(self):
        output, summary = self.check({"DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY"})
        self.assertEqual("configured=true\n", output)
        self.assertEqual("", summary)


class SiteSyncTest(unittest.TestCase):
    def test_the_workflow_rsyncs_with_the_deploy_key(self):
        with tempfile.TemporaryDirectory() as temp:
            remote = deploy_site.Remote.from_environment({
                "DEPLOY_HOST": "box.example", "DEPLOY_USER": "root", "DEPLOY_KEY": "KEY", "RUNNER_TEMP": temp,
            })
            command = deploy_site.rsync_command(remote, pathlib.Path("/repo/site"), pathlib.Path("/var/www/x"), deploy_site.SITE_SWITCHES)
            key = pathlib.Path(temp) / "deploy_key"
            self.assertEqual(
                ["rsync", "-rltvz", "--delete", "--chmod=D755,F644",
                 "-e", f"ssh -i {key} -o StrictHostKeyChecking=no -o IdentitiesOnly=yes",
                 "/repo/site/", "root@box.example:/var/www/x/"],
                command,
            )

    def test_a_laptop_rsyncs_through_its_host_alias(self):
        remote = deploy_site.Remote.from_environment({})
        self.assertEqual(
            ["rsync", "-rltvz", "--delete", "--chmod=D755,F644", "/repo/site/", "hetzner_gb:/var/www/x/"],
            deploy_site.rsync_command(remote, pathlib.Path("/repo/site"), pathlib.Path("/var/www/x"), deploy_site.SITE_SWITCHES),
        )


class NginxTest(unittest.TestCase):
    def config(self):
        return (deploy_site.NGINX_DIR / f"{deploy_site.SITE_HOST}.conf").read_text()

    def test_the_site_names_the_certificate_it_needs(self):
        self.assertEqual([deploy_site.SITE_HOST], deploy_site.certificates(self.config()))
        self.assertEqual([], deploy_site.certificates("# ssl_certificate /etc/letsencrypt/live/x/fullchain.pem"))

    def test_the_site_is_served_from_where_it_is_synced_to(self):
        config = self.config()
        self.assertIn(f"root {deploy_site.WEB_ROOT};", config)
        self.assertEqual(2, config.count(f"server_name {deploy_site.SITE_HOST};"))
        self.assertIn(f"return 301 https://{deploy_site.SITE_HOST}$request_uri;", config)

    def test_the_site_is_files_and_nothing_else(self):
        """Again has no server, so nothing on its site is handed on to one."""
        self.assertNotIn("proxy_pass", self.config())
        for path in sorted(deploy_site.SITE_DIR.glob("*.html")):
            self.assertNotIn("fetch(", path.read_text(), path.name)


class SitePagesTest(unittest.TestCase):
    """Every local link and image in site/ resolves the way nginx's try_files does."""

    class Links(html.parser.HTMLParser):
        def __init__(self):
            super().__init__()
            self.urls, self.ids = [], set()

        def handle_starttag(self, tag, attrs):
            attrs = dict(attrs)
            if "id" in attrs:
                self.ids.add(attrs["id"])
            for key in ("href", "src", "srcset"):
                if attrs.get(key):
                    self.urls.append(attrs[key])

    def pages(self):
        parsed = {}
        for path in sorted(deploy_site.SITE_DIR.glob("*.html")):
            links = self.Links()
            links.feed(path.read_text())
            parsed[path] = links
        return parsed

    def resolve(self, url):
        """The file nginx serves for a local path: the file itself, `<path>.html`, or an index."""
        relative = url.lstrip("/")
        for candidate in (relative, f"{relative}.html", f"{relative}/index.html".lstrip("/")):
            path = deploy_site.SITE_DIR / candidate
            if candidate and path.is_file():
                return path
        return None

    def test_the_pages_the_stores_link_to_exist(self):
        pages = self.pages()
        for page in ("index.html", "privacy.html", "support.html", "404.html"):
            self.assertIn(deploy_site.SITE_DIR / page, pages)

    def test_local_links_resolve(self):
        pages = self.pages()
        for page, links in pages.items():
            for url in links.urls:
                if not url.startswith("/"):
                    continue
                path, _, fragment = url.partition("#")
                target = self.resolve(path) or self.fail(f"{page.name}: {url} does not exist")
                if fragment:
                    self.assertIn(fragment, pages[target].ids, f"{page.name}: {url}")

    def test_every_page_gives_the_contact_address(self):
        for page in self.pages():
            self.assertIn(CONTACT, page.read_text(), page.name)

    def test_every_page_links_the_privacy_policy(self):
        for page, links in self.pages().items():
            self.assertIn("/privacy", links.urls, page.name)


class AppLinkTest(unittest.TestCase):
    def test_the_app_links_to_the_privacy_policy_the_site_serves(self):
        links = re.findall(r'const val PRIVACY_POLICY = "([^"]+)"', LINKS.read_text())
        self.assertEqual([deploy_site.PRIVACY_URL], links)
        page = deploy_site.PRIVACY_URL.removeprefix(f"https://{deploy_site.SITE_HOST}")
        self.assertIsNotNone(SitePagesTest().resolve(page), page)


class WorkflowTest(unittest.TestCase):
    """The workflow hands the script what it expects, and runs on what the site is made of."""

    def test_the_workflow_passes_every_secret_the_script_checks(self):
        workflow = WORKFLOW.read_text()
        for name in deploy_site.SECRETS:
            self.assertIn(f"{name}: ${{{{ secrets.{name} }}}}", workflow)

    def test_the_workflow_runs_every_command_in_order(self):
        workflow = WORKFLOW.read_text()
        positions = [workflow.index(f"python3 tools/deploy_site.py {command}") for command in deploy_site.COMMANDS]
        self.assertEqual(sorted(positions), positions)

    def test_without_the_secrets_nothing_reaches_the_box(self):
        workflow = WORKFLOW.read_text()
        for command in ("nginx", "site"):
            step = workflow[:workflow.index(f"python3 tools/deploy_site.py {command}")].rsplit("- name:", 1)[1]
            self.assertIn("if: steps.secrets.outputs.configured == 'true'", step, command)

    def test_a_change_to_the_site_deploys_it(self):
        triggers = WORKFLOW.read_text().split("jobs:", 1)[0]
        self.assertIn("branches: [main]", triggers)
        self.assertNotIn("pull_request:", triggers)
        for path in ("site/**", "deploy/nginx/**", "tools/deploy_site.py", ".github/workflows/deploy-site.yml"):
            self.assertIn(f'- "{path}"', triggers)

    def test_these_tests_run_before_every_deploy_and_with_the_other_scripts(self):
        self.assertIn("python3 -m unittest discover -s tools -p 'test_deploy_site.py'", WORKFLOW.read_text())
        android = ANDROID_WORKFLOW.read_text()
        self.assertIn("python3 -m unittest discover -s tools -p 'test_*.py'", android)
        self.assertIn('- "tools/**"', android)


if __name__ == "__main__":
    unittest.main()
