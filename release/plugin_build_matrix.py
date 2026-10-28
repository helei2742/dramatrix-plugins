#!/usr/bin/env python3
"""Select changed Profile or Media plugin modules for isolated CI builds."""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess


REPOSITORY_ROOT = pathlib.Path('.')
GLOBAL_BUILD_FILES = {
    pathlib.Path('pom.xml'),
    pathlib.Path('dramatrix-plugin/pom.xml'),
    pathlib.Path('release/plugin_package.py'),
    pathlib.Path('release/build-plugin-packages.sh'),
    pathlib.Path('release/plugin_build_matrix.py'),
}
TYPE_WORKFLOWS = {
    'profile': pathlib.Path('.github/workflows/plugin-profile-build.yml'),
    'media': pathlib.Path('.github/workflows/plugin-media-build.yml'),
}


def changed_files(base: str, head: str) -> set[pathlib.Path]:
    result = subprocess.run(
        ['git', 'diff', '--name-only', '--diff-filter=ACMRT', f'{base}...{head}'],
        check=True,
        capture_output=True,
        text=True,
    )
    return {pathlib.Path(line) for line in result.stdout.splitlines() if line.strip()}


def plugin_modules(plugin_type: str) -> dict[str, pathlib.Path]:
    if plugin_type == 'profile':
        root = REPOSITORY_ROOT / 'dramatrix-plugin/profile'
        result = {}
        for manifest in sorted(root.glob('profile-plugin-*/src/main/profile/plugin.json')):
            module = manifest.parents[3]
            plugin_id = module.name.removeprefix('profile-plugin-')
            result[plugin_id] = module
        return result

    if plugin_type == 'media':
        result = {}
        for source_root in (REPOSITORY_ROOT / 'dramatrix-plugin/comfyui',
                            REPOSITORY_ROOT / 'dramatrix-plugin/api'):
            for manifest in sorted(source_root.glob('**/src/main/java/**/adapter.json')):
                module = next((parent for parent in manifest.parents if (parent / 'pom.xml').is_file()), None)
                if module is None:
                    raise ValueError(f'could not find module POM for {manifest}')
                plugin_id = json.loads(manifest.read_text(encoding='utf-8')).get('name')
                if not isinstance(plugin_id, str) or not plugin_id:
                    raise ValueError(f'adapter manifest has no plugin name: {manifest}')
                if plugin_id in result:
                    raise ValueError(f'duplicate {plugin_type} plugin ID: {plugin_id}')
                result[plugin_id] = module
        return result

    raise ValueError(f'unsupported plugin type: {plugin_type}')


def selected_plugins(files: set[pathlib.Path], plugin_type: str) -> list[dict[str, str]]:
    modules = plugin_modules(plugin_type)
    if files & (GLOBAL_BUILD_FILES | {TYPE_WORKFLOWS[plugin_type]}):
        selected = modules
    else:
        selected = {
            plugin_id: module
            for plugin_id, module in modules.items()
            if any(path == module or module in path.parents for path in files)
        }
    return [
        {'id': plugin_id, 'module': module.as_posix()}
        for plugin_id, module in sorted(selected.items())
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--type', choices=('profile', 'media'), required=True)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    files = changed_files(args.base, args.head)
    print(json.dumps(selected_plugins(files, args.type), separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
