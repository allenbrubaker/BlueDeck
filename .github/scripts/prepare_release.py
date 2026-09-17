"""Validate release metadata without creating tags or contacting GitHub."""

import os
from pathlib import Path
import re
import subprocess
import sys


def git(*args):
    return subprocess.run(["git", *args], text=True, capture_output=True, check=True).stdout.strip()


def prepare_release():
    event = os.environ.get("GITHUB_EVENT_NAME")
    if event == "workflow_dispatch":
        tag = os.environ.get("INPUT_TAG", "")
    elif event == "push" and os.environ.get("GITHUB_REF_TYPE") == "tag":
        tag = os.environ.get("GITHUB_REF_NAME", "")
    else:
        raise ValueError("Releases require a tag push or a manual workflow run.")

    number = r"(?:0|[1-9][0-9]*)"
    if not re.fullmatch(rf"v{number}\.{number}\.{number}", tag):
        raise ValueError("Release tag must have the form vMAJOR.MINOR.PATCH, e.g. v1.14.0.")
    version = tag[1:]
    versions = re.findall(
        r'^\s*versionName\s*=\s*"([^"]+)"\s*$',
        Path("app/build.gradle.kts").read_text(),
        re.MULTILINE,
    )
    if versions != [version]:
        raise ValueError("Release tag must match the single versionName in app/build.gradle.kts.")
    if not re.search(
        rf"^## \[{re.escape(version)}\] - \d{{4}}-\d{{2}}-\d{{2}}$",
        Path("CHANGELOG.md").read_text(),
        re.MULTILINE,
    ):
        raise ValueError("CHANGELOG.md must contain a dated section for the release version.")

    commit = git("rev-parse", "HEAD")
    ref = f"refs/tags/{tag}"
    exists = subprocess.run(["git", "show-ref", "--verify", "--quiet", ref])
    if exists.returncode == 0:
        if git("rev-parse", f"{ref}^{{commit}}") != commit:
            raise ValueError("Release tag already points to another commit; tags are never moved.")
    elif exists.returncode != 1:
        raise ValueError("Could not check existing release tags.")
    elif event == "push":
        raise ValueError("The pushed release tag is missing from the checkout.")

    return {"tag": tag, "version": version, "commit": commit}


if __name__ == "__main__":
    try:
        release = prepare_release()
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            for key, value in release.items():
                output.write(f"{key}={value}\n")
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
