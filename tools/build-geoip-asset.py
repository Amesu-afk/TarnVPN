#!/usr/bin/env python3
"""Build the bundled IPv4 country database used by the location map.

The app resolves the selected server's host itself. This file turns DB-IP Lite's country CSV
into a tiny binary the app can binary-search without a network request:

    magic      4 bytes   "TGI1"
    count      2 bytes   number of country codes (big endian)
    codes      2*count   ISO-3166 alpha-2, ASCII, sorted by index
    ranges     4 bytes   number of IPv4 ranges
    starts     4*ranges  uint32 range start, ascending, big endian
    codeByIdx  1*ranges  index into codes

There are no ends: the ranges cover 0.0.0.0-255.255.255.255 without holes (the script fills
any hole with ZZ), so a range ends where the next one starts, minus one.

Source data: DB-IP Lite, https://db-ip.com — licensed CC BY 4.0
(https://creativecommons.org/licenses/by/4.0/). Keep the attribution in the app: it is shown
on the Shield screen and repeated in app/src/main/assets/geoip-NOTICE.txt.

Usage:
    python tools/build-geoip-asset.py                  # current month, downloads the CSV
    python tools/build-geoip-asset.py --input file.gz  # a previously downloaded CSV
"""

import argparse
import datetime as dt
import gzip
import ipaddress
import pathlib
import struct
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET = ROOT / "app" / "src" / "main" / "assets" / "geoip-country.bin"
NOTICE = ROOT / "app" / "src" / "main" / "assets" / "geoip-NOTICE.txt"
MAX_IP = 0xFFFFFFFF


def open_csv(path: pathlib.Path):
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8")
    return path.open(encoding="utf-8")


def download(month: str, destination: pathlib.Path) -> pathlib.Path:
    url = f"https://download.db-ip.com/free/dbip-country-lite-{month}.csv.gz"
    print(f"downloading {url}")
    with urllib.request.urlopen(url, timeout=120) as response:
        destination.write_bytes(response.read())
    return destination


def read_ranges(path: pathlib.Path):
    records = []
    skipped_v6 = 0
    with open_csv(path) as source:
        for line in source:
            line = line.strip()
            if not line:
                continue
            start_text, end_text, code = line.split(",")
            if ":" in start_text:
                skipped_v6 += 1
                continue
            if len(code) != 2:
                raise SystemExit(f"unexpected country code {code!r}")
            start = int(ipaddress.IPv4Address(start_text))
            end = int(ipaddress.IPv4Address(end_text))
            if end < start:
                raise SystemExit(f"range ends before it starts: {line}")
            records.append((start, end, code))
    records.sort()
    print(f"IPv4 ranges: {len(records)}, IPv6 skipped: {skipped_v6}")
    return records


def fill_holes(records):
    filled = []
    cursor = 0
    for start, end, code in records:
        if start > cursor:
            filled.append((cursor, start - 1, "ZZ"))
        filled.append((start, end, code))
        cursor = end + 1
    if cursor <= MAX_IP:
        filled.append((cursor, MAX_IP, "ZZ"))
    return filled


def write_asset(records, output: pathlib.Path):
    codes = sorted({code for _, _, code in records})
    if len(codes) > 255:
        raise SystemExit(f"too many country codes for one-byte indices: {len(codes)}")
    code_index = {code: index for index, code in enumerate(codes)}

    raw = bytearray()
    raw += b"TGI1"
    raw += struct.pack(">H", len(codes))
    for code in codes:
        raw += code.encode("ascii")
    raw += struct.pack(">I", len(records))
    for start, _, _ in records:
        raw += struct.pack(">I", start)
    for _, _, code in records:
        raw += bytes((code_index[code],))

    output.parent.mkdir(parents=True, exist_ok=True)
    # Not gzipped on purpose: aapt2 recognises a .gz asset, unpacks it and drops the suffix,
    # so a "geoip-country.bin.gz" in the source tree becomes "geoip-country.bin" in the APK.
    # The APK zip still compresses this file, so the installed size is the same.
    output.write_bytes(raw)
    print(f"wrote {output} ({len(raw)} bytes)")
    print(f"countries: {len(codes)}, ranges: {len(records)}")
    return codes


def write_notice(month: str):
    NOTICE.write_text(
        "IP geolocation data: DB-IP Lite (https://db-ip.com).\n"
        "Licensed under Creative Commons Attribution 4.0 International\n"
        "(https://creativecommons.org/licenses/by/4.0/).\n"
        f"Dataset: {month}.\n"
        "Modified: converted to a compact binary IPv4 range table for offline lookup; the\n"
        "country assignments themselves are unchanged.\n"
        "Bundled offline; the app never sends an address to a geolocation service.\n",
        encoding="utf-8",
    )
    print(f"wrote {NOTICE}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path, help="local dbip-country-lite CSV(.gz)")
    parser.add_argument("--month", help="YYYY-MM for the download, defaults to this month")
    args = parser.parse_args()

    month = args.month or dt.date.today().strftime("%Y-%m")
    if args.input:
        source = args.input
    else:
        source = download(month, ROOT / "build" / f"dbip-country-lite-{month}.csv.gz")

    records = fill_holes(read_ranges(source))
    if not records or records[0][0] != 0 or records[-1][1] != MAX_IP:
        raise SystemExit("the filled range table does not cover IPv4")
    write_asset(records, ASSET)
    write_notice(month)


if __name__ == "__main__":
    sys.exit(main())
