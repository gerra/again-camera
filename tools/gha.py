"""Helpers shared by the Python steps in .github/workflows.

The workflows call `python3 tools/<script>.py <command>` instead of carrying shell inline.
These are the few things those scripts all need: running a command, stopping with an error
the Actions log highlights, and writing to the three files Actions reads back — step
outputs, the job environment and the run summary.
"""

import os
import pathlib
import subprocess
import sys
import uuid


def run(*command, show=True, check=True, capture=False, **kwargs):
    """Run a command given as separate arguments.

    Nothing goes through a shell, so no argument is re-parsed and values with spaces or
    quotes need no escaping. `show` echoes the command the way a shell step would; turn it
    off for a command whose arguments carry a secret. Returns the CompletedProcess, so
    `check=False` lets the caller look at `returncode`.
    """
    if show:
        print("$ " + " ".join(str(c) for c in command), flush=True)
    return subprocess.run(
        [str(c) for c in command], check=check, text=True, capture_output=capture, **kwargs
    )


def stdout(*command, **kwargs):
    """The stripped standard output of a command."""
    return run(*command, capture=True, **kwargs).stdout.strip()


def fail(message):
    """Stop the step, with the message shown as an error annotation on the run."""
    print(f"::error::{message}")
    sys.exit(1)


def _write(variable, values):
    path = os.environ.get(variable)
    if not path:  # Outside Actions: say what would have been written and move on.
        print(f"[{variable}] " + ", ".join(f"{k}={v}" for k, v in values.items()))
        return
    with open(path, "a") as handle:
        for key, value in values.items():
            if "\n" in str(value):  # Multi-line values need the delimiter form.
                marker = f"EOF_{uuid.uuid4().hex}"
                handle.write(f"{key}<<{marker}\n{value}\n{marker}\n")
            else:
                handle.write(f"{key}={value}\n")


def output(**values):
    """Set step outputs, read elsewhere as steps.<id>.outputs.<name>."""
    _write("GITHUB_OUTPUT", values)


def export(**values):
    """Set environment variables for the later steps of this job."""
    _write("GITHUB_ENV", values)


def summary(text, echo=True):
    """Add a line to the run summary — the page shown under the finished job."""
    if echo:
        print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a") as handle:
            handle.write(text + "\n")


def secret(name):
    """A required secret, passed in through the environment."""
    value = os.environ.get(name)
    if not value:
        fail(f"Repository secret {name} is not set (see docs/android.md)")
    return value


def temp(*parts):
    """A path under the runner's temporary directory."""
    return pathlib.Path(os.environ.get("RUNNER_TEMP", "/tmp")).joinpath(*parts)
