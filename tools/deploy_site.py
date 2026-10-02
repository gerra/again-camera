#!/usr/bin/env python3
"""Putting again.gerra.sh on its box, for .github/workflows/deploy-site.yml.

The app's own site, the way gerra/gains ships gains.gerra.sh: the static pages in site/
(landing page, privacy policy, support), served by nginx on the same Hetzner box. Google Play
and the App Store link to its privacy policy, and so does the app. Three commands, in the order
the workflow runs them:

  check-secrets  Say whether the DEPLOY_* secrets are all set. A missing one is not an error
                 here: the step's `configured` output turns the rest of the job off and the run
                 summary names it, so the workflow can exist before the box is set up for it.
  nginx          Push every site in deploy/nginx/ whose certificate exists, `nginx -t`, reload.
  site           Rsync site/ to the web root on the box, then smoke test /privacy.

`nginx` and `site` also run from a laptop, against the host alias in REMOTE (hetzner_gb by
default), when the workflow isn't wanted. One-time setup: docs/site.md

Nothing here goes through a shell: every command is a list of arguments, so paths and secrets
are never re-parsed.
"""

import argparse
import os
import pathlib
import re
import shlex
import urllib.error
import urllib.request

import gha

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The box: the same three secrets as gerra/gains' deploy workflows.
SECRETS = ["DEPLOY_HOST", "DEPLOY_USER", "DEPLOY_KEY"]
DEFAULT_REMOTE = "hetzner_gb"

# nginx: each deploy/nginx/<name>.conf becomes sites-available/<name>, linked from sites-enabled.
NGINX_DIR = ROOT / "deploy" / "nginx"
CERTIFICATE = re.compile(r"^\s*ssl_certificate\s+/etc/letsencrypt/live/([^/\s]+)/", re.MULTILINE)

# The site: static files, no build step. nginx (www-data) reads them, so not under /root.
SITE_DIR = ROOT / "site"
SITE_HOST = "again.gerra.sh"
WEB_ROOT = pathlib.Path("/var/www") / SITE_HOST
# What the stores and the app link to (composeApp/.../Links.kt), and what the deploy checks.
PRIVACY_URL = f"https://{SITE_HOST}/privacy"

# Readable by nginx's worker, whoever owns the files here.
SITE_SWITCHES = ["-rltvz", "--delete", "--chmod=D755,F644"]


# --- talking to the box ------------------------------------------------------


class Remote:
    """One box, reached either through a host alias (laptop) or user@host with a key (CI)."""

    def __init__(self, target, key=None):
        self.target = target
        self.key = key

    @classmethod
    def from_environment(cls, env=None):
        """The deploy secrets when they are set (CI), else the REMOTE host alias (laptop)."""
        env = os.environ if env is None else env
        host, user, key = (env.get(name) for name in SECRETS)
        if host and user and key:
            path = pathlib.Path(env.get("RUNNER_TEMP", "/tmp")) / "deploy_key"
            path.write_text(key if key.endswith("\n") else key + "\n")
            path.chmod(0o600)
            return cls(f"{user}@{host}", key=path)
        return cls(env.get("REMOTE", DEFAULT_REMOTE))

    def options(self):
        if self.key is None:
            return []
        return ["-i", str(self.key), "-o", "StrictHostKeyChecking=no", "-o", "IdentitiesOnly=yes"]

    def ssh(self, *command, check=True):
        """Run a command on the box. Arguments are joined for the remote shell, so keep them simple."""
        return gha.run("ssh", *self.options(), self.target, *command, check=check)

    def scp(self, source, destination):
        gha.run("scp", "-q", *self.options(), str(source), f"{self.target}:{destination}")


def rsync_command(remote, source, destination, switches):
    """rsync of a directory's contents into another, deleting what is gone from the source."""
    command = ["rsync", *switches]
    if remote.options():
        command += ["-e", shlex.join(["ssh", *remote.options()])]
    return command + [f"{source}/", f"{remote.target}:{destination}/"]


def fetch_status(url):
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, OSError):
        return 0


def certificates(config):
    """The Let's Encrypt names a site's configuration needs a certificate for."""
    return sorted(set(CERTIFICATE.findall(config)))


# --- the commands --------------------------------------------------------------


def check_secrets(args):
    """`configured=true` when every deploy secret is set; otherwise `false`, and the job skips.

    Not a failure: the workflow is in the repository before the box has a certificate for the
    site or the repository has the secrets, and a push to main then should say what is missing
    rather than go red.
    """
    missing = [name for name in SECRETS if not os.environ.get(name)]
    if missing:
        gha.summary(
            "Site deploy skipped: repository secret(s) "
            + ", ".join(f"`{name}`" for name in missing)
            + " not set (see docs/site.md)."
        )
        gha.output(configured="false")
        return
    print(f"All {len(SECRETS)} deploy secrets are set.")
    gha.output(configured="true")


def nginx(args):
    """Push each site in deploy/nginx/ whose certificate exists, then test and reload nginx."""
    remote = Remote.from_environment()
    pushed = []
    for path in sorted(NGINX_DIR.glob("*.conf")):
        site_name = path.stem
        missing = [
            domain for domain in certificates(path.read_text())
            if remote.ssh("test", "-f", f"/etc/letsencrypt/live/{domain}/fullchain.pem", check=False).returncode != 0
        ]
        if missing:
            print(
                f"skipping {site_name}: no certificate; issue it first (needs the DNS record): "
                + "; ".join(f"ssh {remote.target} 'certbot certonly --nginx -d {domain}'" for domain in missing)
            )
            continue
        available = f"/etc/nginx/sites-available/{site_name}"
        remote.scp(path, available)
        remote.ssh("ln", "-sfn", available, f"/etc/nginx/sites-enabled/{site_name}")
        pushed.append(site_name)
    if not pushed:
        gha.fail("nothing pushed: no site in deploy/nginx/ has its certificate on the box (see docs/site.md)")
    remote.ssh("nginx", "-t")
    remote.ssh("systemctl", "reload", "nginx")
    print(f"pushed {', '.join(pushed)}; nginx reloaded.")


def site(args):
    remote = Remote.from_environment()
    remote.ssh("mkdir", "-p", str(WEB_ROOT))
    gha.run(*rsync_command(remote, SITE_DIR, WEB_ROOT, SITE_SWITCHES))
    status = fetch_status(PRIVACY_URL)
    if status != 200:
        gha.fail(f"{PRIVACY_URL} answered {status or 'nothing'}; is the vhost pushed (`nginx`)?")
    gha.summary(f"Site deployed: {PRIVACY_URL} answers 200.")


# In the order the workflow runs them.
COMMANDS = {
    "check-secrets": (check_secrets, "CI: say whether the DEPLOY_* secrets are set"),
    "nginx": (nginx, "CI or laptop: push every site in deploy/nginx/ and reload nginx"),
    "site": (site, "CI or laptop: rsync site/ to the web root on the box, smoke test /privacy"),
}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name, (handler, help_text) in COMMANDS.items():
        commands.add_parser(name, help=help_text).set_defaults(handler=handler)
    args = parser.parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    main()
