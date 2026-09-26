#!/usr/bin/env python3
"""Clean, categorize, check and rebuild the channel list.

channels.json is the source of truth. This script:
  1. removes duplicate entries (same stream URL),
  2. assigns each channel a group (category),
  3. optionally checks every stream and tracks consecutive failures,
  4. optionally imports extra channels from the M3U lists in sources.json,
  5. optionally attaches logos / tvg-id from the public iptv-org database,
  6. writes channels.json back and regenerates playlist.m3u
     (and playlist_adult.m3u for channels marked "playlist": "adult").

Only the Python standard library is used.

Usage:
  python3 scripts/update_playlist.py                 # clean + rebuild, no network
  python3 scripts/update_playlist.py --check         # also test every stream
  python3 scripts/update_playlist.py --check --logos # also fetch logos
  python3 scripts/update_playlist.py --import --check --logos  # full daily run
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
ADULT_PLAYLIST_FILE = ROOT / "playlist_adult.m3u"
SOURCES_FILE = ROOT / "sources.json"

USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
TIMEOUT = 12
WORKERS = 64

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
GROUP_ORDER = [g for g, _ in GROUPS] + [DEFAULT_GROUP] + [
    "قنوات عربية عامة", "ترفيه عام", "أفلام", "مسلسلات", "كوميديا", "موسيقى", "أطفال",
    "أخبار", "ثقافة ووثائقي", "ديني", "أفلام عالمية", "مسلسلات عالمية",
]
# Any other imported group sorts after these, in first-seen order.
# In sources.json, this group name means "sort into the sports sub-groups by name".
SPORTS_MARKER = "@sports"

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

def load_channels() -> tuple[list[dict], set[str]]:
    data = json.loads(CHANNELS_FILE.read_text(encoding="utf-8"))
    raw = data.get("channels_list", data if isinstance(data, list) else [])
    channels = [c for c in raw if isinstance(c, dict) and c.get("name") and c.get("url")]
    removed = set(data.get("removed_urls", [])) if isinstance(data, dict) else set()
    return channels, removed


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


# ---------------------------------------------------------------- import

EXTINF_ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')


def parse_m3u(text: str) -> list[dict]:
    """Parse an extended M3U into [{name, url, attrs}]."""
    entries: list[dict] = []
    pending: dict | None = None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            info, _, name = line.partition(",")
            pending = {"name": name.strip(), "attrs": dict(EXTINF_ATTR_RE.findall(info)), "opts": []}
        elif line.startswith("#EXTVLCOPT:") and pending is not None:
            # Player hints such as http-user-agent / http-referrer that some streams require.
            pending["opts"].append(line[len("#EXTVLCOPT:"):])
        elif line and not line.startswith("#") and pending is not None:
            if pending["name"] and line.startswith(("http://", "https://")):
                pending["url"] = line
                entries.append(pending)
            pending = None
    return entries


def pick_group(group_title: str, categories: dict[str, str]) -> str | None:
    """Map a source group-title like 'Entertainment;Movies' to one of our groups."""
    if "*" in categories:
        return categories["*"]
    for cat in re.split(r"[;,]", group_title):
        cat = cat.strip()
        if cat in categories:
            return categories[cat]
    return None


def fetch_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="replace")


def import_sources(channels: list[dict], removed: set[str]) -> int:
    """Add channels from sources.json that aren't already known. Never breaks the run."""
    if not SOURCES_FILE.exists():
        return 0
    sources = json.loads(SOURCES_FILE.read_text(encoding="utf-8")).get("sources", [])
    known = {c["url"] for c in channels} | removed
    added = 0
    for src in sources:
        try:
            entries = parse_m3u(fetch_text(src["url"]))
        except Exception as exc:  # noqa: BLE001 - a dead source must not stop the update
            print(f"  import {src.get('id', src['url'])}: skipped ({exc})")
            continue
        count = 0
        for e in entries:
            url = normalize_url(e["url"])
            group = pick_group(e["attrs"].get("group-title", ""), src.get("categories", {}))
            if not group or url in known:
                continue
            if group == SPORTS_MARKER:
                group = classify(e["name"])
            ch = {"name": e["name"], "url": url, "group": group, "source": src["id"]}
            if src.get("playlist", "main") != "main":
                ch["playlist"] = src["playlist"]
            if e["attrs"].get("tvg-id"):
                ch["tvg_id"] = e["attrs"]["tvg-id"]
            if e["attrs"].get("tvg-logo"):
                ch["logo"] = e["attrs"]["tvg-logo"]
            if e["opts"]:
                ch["vlc_opts"] = e["opts"]
            channels.append(ch)
            known.add(url)
            count += 1
        print(f"  import {src['id']}: {count} new of {len(entries)}")
        added += count
    return added


# ---------------------------------------------------------------- stream check

def stream_headers(ch: dict) -> dict[str, str]:
    headers = {"User-Agent": USER_AGENT}
    for opt in ch.get("vlc_opts", []):
        key, _, value = opt.partition("=")
        if key == "http-user-agent":
            headers["User-Agent"] = value
        elif key == "http-referrer":
            headers["Referer"] = value
    return headers


def check_stream(url: str, headers: dict[str, str] | None = None) -> bool:
    req = urllib.request.Request(url, headers=headers or {"User-Agent": USER_AGENT})
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
        results = pool.map(lambda c: check_stream(c["url"], stream_headers(c)), channels)
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
    if ch["group"] not in GROUP_ORDER:
        GROUP_ORDER.append(ch["group"])
    return (ch.get("playlist", "main") != "main", GROUP_ORDER.index(ch["group"]), ch["name"].lower())


def m3u_attr(value: str) -> str:
    return value.replace('"', "'")


def write_playlist(channels: list[dict], path: Path) -> int:
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
        lines.extend(f"#EXTVLCOPT:{opt}" for opt in ch.get("vlc_opts", []))
        lines.append(ch["url"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(visible)


FIELD_ORDER = [
    "name", "url", "group", "playlist", "source", "tvg_id", "logo", "vlc_opts",
    "fail_count", "last_ok",
]


def write_channels(channels: list[dict], removed: set[str]) -> None:
    ordered = []
    for ch in channels:
        item = {k: ch[k] for k in FIELD_ORDER if k in ch and ch[k] not in (None, "")}
        item.update({k: v for k, v in ch.items() if k not in item and k not in FIELD_ORDER})
        ordered.append(item)
    data = {"last_update": today(), "total": len(ordered), "channels_list": ordered}
    if removed:
        # Dead imported URLs are remembered so the importer doesn't re-add them.
        data["removed_urls"] = sorted(removed)
    CHANNELS_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


# ---------------------------------------------------------------- main

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="test every stream")
    parser.add_argument("--logos", action="store_true", help="fetch logos from iptv-org")
    parser.add_argument("--import", dest="do_import", action="store_true",
                        help="add channels from the lists in sources.json")
    args = parser.parse_args(argv)

    channels, removed = load_channels()
    print(f"loaded: {len(channels)} channels")

    channels, dupes = dedupe(channels)
    print(f"duplicates removed: {dupes}")

    for ch in channels:
        # Imported channels keep the group their source assigned.
        if not ch.get("source"):
            ch["group"] = classify(ch["name"])
        elif ch.get("group") == SPORTS_MARKER:
            ch["group"] = classify(ch["name"])

    if args.do_import:
        print(f"imported: {import_sources(channels, removed)}")

    if args.check:
        ok, bad = run_checks(channels)
        print(f"check: {ok} working, {bad} failed")
        dead = [c for c in channels if int(c.get("fail_count", 0)) >= REMOVE_AFTER]
        removed.update(c["url"] for c in dead if c.get("source"))
        channels = [c for c in channels if int(c.get("fail_count", 0)) < REMOVE_AFTER]
        print(f"removed after {REMOVE_AFTER} consecutive failures: {len(dead)}")

    if args.logos:
        print(f"logos added: {attach_logos(channels)}")

    channels.sort(key=sort_key)
    write_channels(channels, removed)
    main_list = [c for c in channels if c.get("playlist", "main") == "main"]
    adult_list = [c for c in channels if c.get("playlist") == "adult"]
    for path, subset in ((PLAYLIST_FILE, main_list), (ADULT_PLAYLIST_FILE, adult_list)):
        if not subset and path == ADULT_PLAYLIST_FILE:
            continue
        shown = write_playlist(subset, path)
        print(f"{path.name}: {shown} channels ({len(subset) - shown} hidden as not working)")

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
