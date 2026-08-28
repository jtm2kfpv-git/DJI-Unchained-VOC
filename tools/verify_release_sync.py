"""Fail when the repository root and its current version snapshot diverge."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def version_name(build_file: Path) -> str:
    match = re.search(r'versionName\s*=\s*"([^"]+)"', build_file.read_text(encoding="utf-8"))
    if match is None:
        raise SystemExit(f"Could not read versionName from {build_file}")
    return match.group(1)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def files_under(base: Path, relative: str) -> dict[str, str]:
    target = base / relative
    if target.is_file():
        return {relative: digest(target)}
    return {
        str(path.relative_to(base)).replace("\\", "/"): digest(path)
        for path in target.rglob("*")
        if path.is_file()
    }


def main() -> None:
    current = version_name(ROOT / "app" / "build.gradle")
    snapshot = ROOT / "versions" / f"v{current}"
    if not snapshot.is_dir():
        raise SystemExit(f"Missing source snapshot: versions/v{current}")

    snapshot_version = version_name(snapshot / "app" / "build.gradle")
    if snapshot_version != current:
        raise SystemExit(
            f"Root version {current} does not match snapshot version {snapshot_version}"
        )

    compared = [
        ".gitignore",
        "app/build.gradle",
        "app/proguard-rules.pro",
        "app/src",
        "build.gradle",
        "gradle",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "settings.gradle",
        "tools/verify_protocol.py",
    ]
    root_files: dict[str, str] = {}
    snapshot_files: dict[str, str] = {}
    for relative in compared:
        root_files.update(files_under(ROOT, relative))
        snapshot_files.update(files_under(snapshot, relative))

    missing = sorted(snapshot_files.keys() - root_files.keys())
    extra = sorted(root_files.keys() - snapshot_files.keys())
    changed = sorted(
        path
        for path in root_files.keys() & snapshot_files.keys()
        if root_files[path] != snapshot_files[path]
    )
    if missing or extra or changed:
        details = []
        if missing:
            details.append("missing from root: " + ", ".join(missing))
        if extra:
            details.append("not preserved in snapshot: " + ", ".join(extra))
        if changed:
            details.append("content differs: " + ", ".join(changed))
        raise SystemExit("Release snapshot drift detected\n" + "\n".join(details))

    versions_text = (ROOT / "VERSIONS.md").read_text(encoding="utf-8")
    changelog_text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    if f"versions/v{current}/" not in versions_text:
        raise SystemExit(f"VERSIONS.md does not link versions/v{current}/")
    if f"## {current}" not in changelog_text:
        raise SystemExit(f"CHANGELOG.md has no {current} section")

    print(f"release sync verified: root == versions/v{current} ({len(root_files)} files)")


if __name__ == "__main__":
    main()
