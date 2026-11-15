#!/usr/bin/env python3
"""
Download, SHA-256 verify, and extract OSS Mozc JNI binaries, mozc.data,
and Meltype dictionaries from AoneSenbongi/Meltype-Android (android-1.3.0, GPL-3.0).
"""

from __future__ import annotations

import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import urllib.request
import zipfile

RELEASE_BASE = "https://github.com/AoneSenbongi/Meltype-Android/releases/download/android-1.3.0"
CACHE_DIR = pathlib.Path(os.environ.get("LOCALAPPDATA", os.path.expanduser("~"))) / "Temp" / "meltype_assets_cache"
PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest().lower()


def download_file(url: str, dest: pathlib.Path, expected_sha256: str | None = None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        if expected_sha256 is None or sha256_file(dest) == expected_sha256:
            print(f"[cache hit] {dest.name} ({dest.stat().st_size:,} bytes)", flush=True)
            return
        print(f"[cache mismatch] re-downloading {dest.name}", flush=True)
        dest.unlink(missing_ok=True)

    part = dest.with_suffix(dest.suffix + ".part")
    curl_exe = shutil.which("curl.exe") or shutil.which("curl")
    print(f"[downloading] {url} -> {dest}", flush=True)

    if curl_exe:
        cmd = [
            curl_exe,
            "-fL",
            "--retry", "4",
            "--retry-delay", "3",
            "--connect-timeout", "30",
            "--max-time", "600",
            "-C", "-",
            "-o", str(part),
            url,
        ]
        res = subprocess.run(cmd, check=False)
        if res.returncode != 0:
            raise RuntimeError(f"curl failed ({res.returncode}) downloading {url}")
    else:
        req = urllib.request.Request(url, headers={"User-Agent": "meltype-android-asset-fetcher/1.0"})
        with urllib.request.urlopen(req, timeout=180) as resp, open(part, "wb") as out:
            while True:
                chunk = resp.read(1024 * 256)
                if not chunk:
                    break
                out.write(chunk)

    os.replace(part, dest)
    print(f"[downloaded] {dest.name} ({dest.stat().st_size:,} bytes)", flush=True)


def parse_sha256sums(path: pathlib.Path) -> dict[str, str]:
    sums: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            digest = parts[0].lower()
            filename = parts[-1].lstrip("*")
            sums[pathlib.Path(filename).name] = digest
    return sums


def extract_from_apk(apk_path: pathlib.Path) -> None:
    targets = {
        "lib/arm64-v8a/libmozc.so": PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libmozc.so",
        "lib/x86_64/libmozc.so": PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / "x86_64" / "libmozc.so",
        "assets/mozc.data": PROJECT_ROOT / "app" / "src" / "main" / "assets" / "mozc.data",
    }
    with zipfile.ZipFile(apk_path, "r") as zf:
        names = set(zf.namelist())
        for zip_entry, dest_path in targets.items():
            if zip_entry not in names:
                raise RuntimeError(f"Expected entry missing in APK: {zip_entry}")
            dest_path.parent.mkdir(parents=True, exist_ok=True)
            info = zf.getinfo(zip_entry)
            if dest_path.exists() and dest_path.stat().st_size == info.file_size:
                print(f"[up-to-date] {dest_path.relative_to(PROJECT_ROOT)} ({info.file_size:,} bytes)", flush=True)
                continue
            with zf.open(zip_entry) as src, open(dest_path, "wb") as dst:
                while True:
                    buf = src.read(1024 * 1024)
                    if not buf:
                        break
                    dst.write(buf)
            print(f"[extracted] {dest_path.relative_to(PROJECT_ROOT)} ({dest_path.stat().st_size:,} bytes)", flush=True)


def extract_from_source_zip(zip_path: pathlib.Path) -> None:
    dict_dest_dir = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "dictionaries"
    dict_dest_dir.mkdir(parents=True, exist_ok=True)
    extracted_dicts = 0
    with zipfile.ZipFile(zip_path, "r") as zf:
        for member in zf.namelist():
            norm = member.replace("\\", "/")
            base = pathlib.Path(norm).name
            if not base or member.endswith("/"):
                continue
            if "/dictionaries/" in norm and base.endswith(".txt"):
                dest = dict_dest_dir / base
                data = zf.read(member)
                dest.write_bytes(data)
                extracted_dicts += 1
                print(f"[extracted dict] {base} ({len(data):,} bytes)", flush=True)
            elif norm.endswith("/LICENSE") or base == "LICENSE":
                dest = PROJECT_ROOT / "LICENSE"
                if not dest.exists():
                    dest.write_bytes(zf.read(member))
                    print("[extracted] LICENSE", flush=True)
            elif base in ("THIRD_PARTY_NOTICES.txt", "THIRD_PARTY_LICENSES.txt", "NOTICE"):
                dest = PROJECT_ROOT / "THIRD_PARTY_NOTICES.txt"
                if not dest.exists():
                    dest.write_bytes(zf.read(member))
                    print(f"[extracted] {base} -> THIRD_PARTY_NOTICES.txt", flush=True)
    if extracted_dicts == 0:
        raise RuntimeError(f"No dictionaries/*.txt found in {zip_path.name}")
    print(f"[done] Extracted {extracted_dicts} dictionary files.", flush=True)


def main() -> int:
    sums_path = CACHE_DIR / "SHA256SUMS.txt"
    src_zip = CACHE_DIR / "corresponding-source-1.3.0.zip"
    apk_file = CACHE_DIR / "Meltype-Android-1.3.0.apk"

    download_file(f"{RELEASE_BASE}/SHA256SUMS.txt", sums_path)
    sums = parse_sha256sums(sums_path)

    for fname, dest in [("corresponding-source-1.3.0.zip", src_zip), ("Meltype-Android-1.3.0.apk", apk_file)]:
        expected = sums.get(fname)
        download_file(f"{RELEASE_BASE}/{fname}", dest, expected_sha256=expected)
        if expected:
            actual = sha256_file(dest)
            if actual != expected:
                print(f"ERROR: SHA-256 mismatch for {fname}: expected={expected}, actual={actual}", file=sys.stderr, flush=True)
                return 1
            print(f"[verified SHA-256] {fname}: {actual}", flush=True)

    extract_from_apk(apk_file)
    extract_from_source_zip(src_zip)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
