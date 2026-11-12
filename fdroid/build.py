#!/usr/bin/env python3
"""
Builds the F-Droid repository and its page for GitHub Pages.

    python3 fdroid/build.py --apks DIR --keystore release.jks --out site [--notes release-notes/v1.2.3.md]

The keystore's password comes from FDROID_KEYSTORE_PASS. The index is signed with the same key
as the APKs (alias "health"), so the repository's fingerprint is the app's signing fingerprint.
Needs fdroidserver (pip install fdroidserver) and an Android SDK (ANDROID_HOME) on the path.
"""
import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP_ID = "app.superawesomemagical.health"
SITE = "https://603madison.github.io/SuperAwesomeMagicalHealth"
REPO_URL = f"{SITE}/fdroid/repo"
RELEASES = "https://github.com/603Madison/SuperAwesomeMagicalHealth/releases"
KEY_ALIAS = "health"


def run(*cmd, cwd=None, capture=False):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], cwd=cwd, check=True, text=True, capture_output=capture)


def fingerprint(keystore: Path, password: str) -> str:
    """The SHA-256 of the signing certificate: what F-Droid checks the repository by."""
    out = run("keytool", "-list", "-v", "-keystore", keystore, "-alias", KEY_ALIAS, "-storepass", password, capture=True).stdout
    match = re.search(r"SHA256:\s*([0-9A-F:]+)", out)
    if not match:
        sys.exit("Couldn't read the key's fingerprint")
    return match.group(1).replace(":", "").upper()


def badging(apk: Path) -> str:
    """What aapt2 says about an APK (its package, versionCode and versionName)."""
    tools = Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")) / "build-tools"
    aapt2 = sorted(tools.glob("*/aapt2"))
    return run(aapt2[-1], "dump", "badging", apk, capture=True).stdout if aapt2 else ""


def version_code(apk: Path) -> int | None:
    """The versionCode, from the APK's own manifest."""
    match = re.search(r"versionCode='(\d+)'", badging(apk))
    return int(match.group(1)) if match else None


def changelog(notes: Path) -> str:
    """Release notes as F-Droid wants a changelog: plain text, 500 characters at most."""
    text = notes.read_text()
    text = re.sub(r"^#+\s*", "", text, flags=re.M)
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) > 500:
        text = text[:480].rsplit(" ", 1)[0] + "… (more in the release notes)"
    return text + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apks", type=Path, required=True, help="Folder of signed release APKs")
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="Where the site goes")
    parser.add_argument("--notes", type=Path, help="The latest release's notes, for its changelog")
    parser.add_argument("--watch", help="The latest watch app's download URL, if there is one")
    args = parser.parse_args()
    password = os.environ.get("FDROID_KEYSTORE_PASS") or sys.exit("Set FDROID_KEYSTORE_PASS")
    apks = sorted(args.apks.glob("*.apk"))
    if not apks:
        sys.exit(f"No APKs in {args.apks}")

    work = Path(tempfile.mkdtemp(prefix="fdroid-"))
    shutil.copytree(HERE / "metadata", work / "metadata")
    (work / "repo").mkdir(parents=True)
    for apk in apks:
        shutil.copy2(apk, work / "repo" / apk.name)
    # fdroid update copies the icon from here into repo/icons.
    shutil.copy2(HERE / "repo-icon.png", work / "repo-icon.png")
    latest = max(apks, key=lambda a: version_code(a) or 0)
    code = version_code(latest)
    if code:
        # The newest version is the current one; its changelog is the release's notes.
        name = re.search(r"versionName='([^']*)'", badging(latest))
        with (work / "metadata" / f"{APP_ID}.yml").open("a") as meta:
            meta.write(f"CurrentVersionCode: {code}\n")
            if name:
                meta.write(f"CurrentVersion: {name.group(1)}\n")
        if args.notes and args.notes.exists():
            logs = work / "metadata" / APP_ID / "en-US" / "changelogs"
            logs.mkdir(parents=True, exist_ok=True)
            (logs / f"{code}.txt").write_text(changelog(args.notes))

    config = work / "config.yml"
    config.write_text(
        "\n".join([
            f"repo_url: {REPO_URL}",
            "repo_name: SuperAwesomeMagicalHealth",
            "repo_description: >-",
            "  The SuperAwesomeMagicalHealth app, signed and published from each release on GitHub.",
            "  You need your own SuperAwesomeMagicalHealth server to sign in to.",
            "repo_icon: repo-icon.png",
            "archive_older: 0",
            f"keystore: {args.keystore.resolve()}",
            f"repo_keyalias: {KEY_ALIAS}",
            "keystorepass: {env: FDROID_KEYSTORE_PASS}",
            "keypass: {env: FDROID_KEYSTORE_PASS}",
            "keydname: CN=SuperAwesomeMagicalHealth, O=SuperAwesomeMagical",
            f"sdk_path: {os.environ.get('ANDROID_HOME', '/opt/android-sdk')}",
            "",
        ])
    )
    config.chmod(0o600)
    run("fdroid", "update", "--rename-apks", "--use-date-from-apk", "--pretty", cwd=work)

    # Check what came out: a signed index that names the app.
    repo = work / "repo"
    for name in ("index-v1.jar", "entry.jar", "index-v2.json"):
        if not (repo / name).exists():
            sys.exit(f"fdroid update didn't make {name}")
    with zipfile.ZipFile(repo / "index-v1.jar") as jar:
        if not any(n.startswith("META-INF/") and n.endswith((".RSA", ".EC", ".DSA")) for n in jar.namelist()):
            sys.exit("The index isn't signed")
    if APP_ID not in (repo / "index-v2.json").read_text():
        sys.exit("The index doesn't list the app")

    fp = fingerprint(args.keystore, password)
    out = args.out
    if out.exists():
        shutil.rmtree(out)
    (out / "fdroid").mkdir(parents=True)
    shutil.copytree(repo, out / "fdroid" / "repo", ignore=shutil.ignore_patterns("status"))
    shutil.copy2(HERE / "repo-icon.png", out / "icon.png")
    (out / ".nojekyll").write_text("")

    add = f"{REPO_URL}?fingerprint={fp}"
    import qrcode  # comes with fdroidserver
    qrcode.make(add, border=2).save(out / "qr.png")
    version = re.search(r"_(\d+)\.apk$", sorted((out / "fdroid" / "repo").glob(f"{APP_ID}_*.apk"))[-1].name)
    page = (HERE / "page.html").read_text()
    for key, value in {
        "REPO_URL": REPO_URL,
        "FINGERPRINT": fp,
        "FINGERPRINT_SPACED": " ".join(fp[i:i + 4] for i in range(0, len(fp), 4)),
        "ADD_URL": add,
        "FDROID_LINK": "fdroidrepos://" + add.removeprefix("https://"),
        "RELEASES": RELEASES,
        "LATEST_APK": f"fdroid/repo/{APP_ID}_{version.group(1)}.apk" if version else RELEASES,
        "WATCH": args.watch or RELEASES,
    }.items():
        page = page.replace("{{" + key + "}}", html.escape(value, quote=True))
    (out / "index.html").write_text(page)
    print(f"Built {out}: {len(apks)} APK(s), fingerprint {fp}")


if __name__ == "__main__":
    main()
