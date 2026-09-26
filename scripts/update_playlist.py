#!/usr/bin/env python3
"""Clean, categorize, check and rebuild the channel list.

channels.json is the source of truth. This script:
  1. removes duplicate entries (same stream URL),
  2. assigns each channel a group (category),
  3. optionally checks every stream and tracks consecutive failures,
  4. optionally attaches logos / tvg-id from the public iptv-org database,
  5. writes channels.json back and regenerates playlist.m3u.

Only the Python standard library is used.

Usage:
  python3 scripts/update_playlist.py                 # clean + rebuild, no network
  python3 scripts/update_playlist.py --check         # also test every stream
  python3 scripts/update_playlist.py --check --logos # also fetch logos
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANNELS_FILE = ROOT / "channels.json"
PLAYLIST_FILE = ROOT / "playlist.m3u"

USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
TIMEOUT = 12
WORKERS = 32

# A channel is hidden from playlist.m3u after this many consecutive failed checks.
HIDE_AFTER = 3
# Channels tagged [Geo-blocked] / [Not 24/7] fail often from the CI runner
# (wrong country / off-air), so they get more slack before being hidden.
HIDE_AFTER_TAGGED = 14
# After this many consecutive failures the channel is removed from channels.json.
# It stays recoverable from git history.
REMOVE_AFTER = 30

IPTV_ORG_CHANNELS = "https://iptv-org.github.io/api/channels.json"
IPTV_ORG_LOGOS = "https://iptv-org.github.io/api/logos.json"

# Order matters: the first matching group wins, and playlist.m3u follows this order.
GROUPS: list[tuple[str, list[str]]] = [
    ("رياضة عربية", [
        "alkass", "bahrain sport", "goal zone", "jordan sport", "ktv sport",
        "oman sport", "sharjah sport", "al masar", "mbc", "dubai sport",
        "abu dhabi sport", "ssc", "on time sport",
    ]),
    ("كرة القدم", [
        "fifa+", "barca", "realmadrid", "real madrid", "mutv", "futbol", "fútbol",
        "football", "fussball", "calcio", "golazo", "gol ", "gol classics", "cazetv",
        "canal do inter", "l1 max", "goal", "футбол",
    ]),
    ("قتال وملاكمة", [
        "mma", "fight", "boxing", "box", "бокс", "боец", "glory", "bellator",
        "combat", "lucha", "fite", "kickboxing", "strongman", "persiana",
    ]),
    ("سباقات سيارات وخيول", [
        "f1 ", "racer", "racing", "nhra", "horse", "equidia", "tjk", "turf",
        "teletrak", "thoroughbred", "lóverseny", "drive", "turbo", "motori",
        "fuel tv", "экстрим",
    ]),
    ("غولف وتنس", ["golf", "tennis"]),
    ("رياضات أمريكية", [
        "nba", "nfl", "mlb", "nhl", "tsn", "nbc sports", "msg", "cbs sports",
        "espn", "fox sports", "pac 12", "fubo", "draftkings", "fanduel",
        "stadium", "usa network", "lacrosse", "pbr",
    ]),
    ("بوكر وألعاب", ["poker", "game", "billiard", "bowling"]),
]
DEFAULT_GROUP = "رياضات متنوعة"
GROUP_ORDER = [g for g, _ in GROUPS] + [DEFAULT_GROUP]

TAG_RE = re.compile(r"\[(geo-blocked|not 24/7)\]", re.IGNORECASE)
QUALITY_RE = re.compile(r"\(\d{3,4}[pi]\)")
BRACKETS_RE = re.compile(r"\[[^\]]*\]|\([^)]*\)")


def today() -> str:
    return dt.date.today().isoformat()


def classify(name: str) -> str:
    lowered = f" {name.lower()} "
    for group, keywords in GROUPS:
        if any(k in lowered for k in keywords):
            return group
    return DEFAULT_GROUP


def base_name(name: str) -> str:
    """'Alkass One (1080p) [Geo-blocked]' -> 'alkass one' (used for logo matching)."""
    return re.sub(r"\s+", " ", BRACKETS_RE.sub("", name)).strip().lower()


def is_tagged(name: str) -> bool:
    return bool(TAG_RE.search(name))


def normalize_url(url: str) -> str:
    return url.strip()


# ---------------------------------------------------------------- load / clean

def load_channels() -> list[dict]:
    data = json.loads(CHANNELS_FILE.read_text(encoding="utf-8"))
    raw = data.get("channels_list", data if isinstance(data, list) else [])
    return [c for c in raw if isinstance(c, dict) and c.get("name") and c.get("url")]


def dedupe(channels: list[dict]) -> tuple[list[dict], int]:
    seen: dict[str, dict] = {}
    for ch in channels:
        ch["name"] = ch["name"].strip()
        ch["url"] = normalize_url(ch["url"])
        key = ch["url"]
        if key in seen:
            # Keep whatever metadata the duplicate carries that the first one lacks.
            for k, v in ch.items():
                seen[key].setdefault(k, v)
            continue
        seen[key] = ch
    return list(seen.values()), len(channels) - len(seen)


# ---------------------------------------------------------------- stream check

def check_stream(url: str) -> bool:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            if resp.status >= 400:
                return False
            head = resp.read(2048)
    except (urllib.error.URLError, OSError, ValueError):
        return False
    content_type = resp.headers.get("Content-Type", "").lower()
    if head.lstrip(b"\xef\xbb\xbf \r\n\t").startswith(b"#EXTM3U"):
        return True
    # Some servers return raw MPEG-TS / DASH instead of an HLS playlist.
    return head[:1] == b"\x47" or "video" in content_type or "dash" in content_type


def run_checks(channels: list[dict]) -> tuple[int, int]:
    ok = bad = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        results = pool.map(lambda c: check_stream(c["url"]), channels)
        for ch, alive in zip(channels, results):
            if alive:
                ch["fail_count"] = 0
                ch["last_ok"] = today()
                ok += 1
            else:
                ch["fail_count"] = int(ch.get("fail_count", 0)) + 1
                bad += 1
    return ok, bad


def hide_threshold(ch: dict) -> int:
    return HIDE_AFTER_TAGGED if is_tagged(ch["name"]) else HIDE_AFTER


def is_visible(ch: dict) -> bool:
    return int(ch.get("fail_count", 0)) < hide_threshold(ch)


# ---------------------------------------------------------------- logos

def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def attach_logos(channels: list[dict]) -> int:
    """Fill tvg_id / logo from iptv-org. Failures here never break the run."""
    try:
        db_channels = fetch_json(IPTV_ORG_CHANNELS)
    except Exception as exc:  # noqa: BLE001 - optional enrichment
        print(f"  logos: skipped, could not load channel database ({exc})")
        return 0
    try:
        db_logos = fetch_json(IPTV_ORG_LOGOS)
    except Exception:  # noqa: BLE001 - older API had logos inline
        db_logos = []

    logo_by_id: dict[str, str] = {}
    for entry in db_logos:
        cid, url = entry.get("channel"), entry.get("url")
        if cid and url and not entry.get("feed"):
            logo_by_id.setdefault(cid, url)

    id_by_name: dict[str, str] = {}
    for entry in db_channels:
        cid = entry.get("id")
        if not cid:
            continue
        if entry.get("logo"):
            logo_by_id.setdefault(cid, entry["logo"])
        for n in [entry.get("name", "")] + list(entry.get("alt_names") or []):
            if n:
                id_by_name.setdefault(base_name(n), cid)

    added = 0
    for ch in channels:
        if ch.get("logo") and ch.get("tvg_id"):
            continue
        cid = ch.get("tvg_id") or id_by_name.get(base_name(ch["name"]))
        if not cid:
            continue
        ch["tvg_id"] = cid
        if not ch.get("logo") and cid in logo_by_id:
            ch["logo"] = logo_by_id[cid]
            added += 1
    return added


# ---------------------------------------------------------------- output

def sort_key(ch: dict):
    return (GROUP_ORDER.index(ch["group"]), ch["name"].lower())


def m3u_attr(value: str) -> str:
    return value.replace('"', "'")


def write_playlist(channels: list[dict]) -> int:
    lines = ["#EXTM3U"]
    name_counts: dict[str, int] = {}
    visible = [c for c in channels if is_visible(c)]
    for ch in visible:
        name = ch["name"]
        name_counts[name] = name_counts.get(name, 0) + 1
        display = name if name_counts[name] == 1 else f"{name} #{name_counts[name]}"
        attrs = []
        if ch.get("tvg_id"):
            attrs.append(f'tvg-id="{m3u_attr(ch["tvg_id"])}"')
        attrs.append(f'tvg-name="{m3u_attr(display)}"')
        if ch.get("logo"):
            attrs.append(f'tvg-logo="{m3u_attr(ch["logo"])}"')
        attrs.append(f'group-title="{m3u_attr(ch["group"])}"')
        lines.append(f"#EXTINF:-1 {' '.join(attrs)},{display}")
        lines.append(ch["url"])
    PLAYLIST_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(visible)


FIELD_ORDER = ["name", "url", "group", "tvg_id", "logo", "fail_count", "last_ok"]


def write_channels(channels: list[dict]) -> None:
    ordered = []
    for ch in channels:
        item = {k: ch[k] for k in FIELD_ORDER if k in ch and ch[k] not in (None, "")}
        item.update({k: v for k, v in ch.items() if k not in item and k not in FIELD_ORDER})
        ordered.append(item)
    data = {"last_update": today(), "total": len(ordered), "channels_list": ordered}
    CHANNELS_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


# ---------------------------------------------------------------- main

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="test every stream")
    parser.add_argument("--logos", action="store_true", help="fetch logos from iptv-org")
    args = parser.parse_args(argv)

    channels = load_channels()
    print(f"loaded: {len(channels)} channels")

    channels, dupes = dedupe(channels)
    print(f"duplicates removed: {dupes}")

    for ch in channels:
        ch["group"] = classify(ch["name"])

    if args.check:
        ok, bad = run_checks(channels)
        print(f"check: {ok} working, {bad} failed")
        before = len(channels)
        channels = [c for c in channels if int(c.get("fail_count", 0)) < REMOVE_AFTER]
        print(f"removed after {REMOVE_AFTER} consecutive failures: {before - len(channels)}")

    if args.logos:
        print(f"logos added: {attach_logos(channels)}")

    channels.sort(key=sort_key)
    write_channels(channels)
    shown = write_playlist(channels)
    print(f"playlist.m3u: {shown} channels ({len(channels) - shown} hidden as not working)")

    counts: dict[str, int] = {}
    for ch in channels:
        if is_visible(ch):
            counts[ch["group"]] = counts.get(ch["group"], 0) + 1
    for group in GROUP_ORDER:
        if group in counts:
            print(f"  {group}: {counts[group]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
