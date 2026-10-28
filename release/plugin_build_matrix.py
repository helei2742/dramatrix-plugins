#!/usr/bin/env python3
"""Select only Profile plugins changed between two Git revisions."""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess


PROFILE_ROOT = pathlib.Path("dramatrix-plugin/profile")
PLUGIN_IDS = (
    "cyberpunk-2099",
    "fantasy-oriental",
    "fantasy-western",
    "japan-fantasy",
    "post-apocalypse",
    "urban-modern",
)
GLOBAL_BUILD_FILES = {
    pathlib.Path("pom.xml"),
    pathlib.Path("dramatrix-plugin/pom.xml"),
    pathlib.Path("release/plugin_package.py"),
    pathlib.Path("release/build-plugin-packages.sh"),
    pathlib.Path("release/plugin_build_matrix.py"),
    pathlib.Path(".github/workflows/plugin-profile-build.yml"),
}


def changed_files(base: str, head: str) -> set[pathlib.Path]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRT", f"{base}...{head}"],
        check=True,
        capture_output=True,
        text=True,
    )
    return {pathlib.Path(line) for line in result.stdout.splitlines() if line.strip()}


def selected_plugins(files: set[pathlib.Path]) -> list[str]:
    if files & GLOBAL_BUILD_FILES:
        return list(PLUGIN_IDS)

    selected = []
    for plugin_id in PLUGIN_IDS:
        module = PROFILE_ROOT / f"profile-plugin-{plugin_id}"
        if any(path == module or module in path.parents for path in files):
            selected.append(plugin_id)
    return selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    files = changed_files(args.base, args.head)
    print(json.dumps(selected_plugins(files), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
