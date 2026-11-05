#!/usr/bin/env python3
"""Rebuilds geodata/geoip.dat and geodata/geosite.dat: the Russian IP ranges and sites that
the apps send past the VPN ("Российские сайты напрямую") and the ad and tracker domains they
block ("Блокировать рекламу").

The full v2fly files are ~25 MB, too much for the 50 MB iOS tunnel, so only the needed
entries are kept. The .dat files are protobuf lists (GeoIPList / GeoSiteList) whose entries
are field 1, each with its tag in field 1; entries are copied byte for byte.

    scripts/update-geodata.py            # download the latest v2fly releases
    scripts/update-geodata.py geoip.dat dlc.dat
"""
import sys
import urllib.request
from pathlib import Path

GEOIP_URL = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
GEOSITE_URL = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
GEOIP_TAGS = {"RU"}
GEOSITE_TAGS = {"CATEGORY-RU", "CATEGORY-ADS-ALL"}
OUT = Path(__file__).resolve().parent.parent / "geodata"


def varint(data: bytes, i: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        b = data[i]
        i += 1
        value |= (b & 0x7F) << shift
        if b < 0x80:
            return value, i
        shift += 7


def fields(data: bytes):
    """Yields (field number, wire type, raw bytes of the whole field, payload) for length-delimited fields."""
    i = 0
    while i < len(data):
        start = i
        key, i = varint(data, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            _, i = varint(data, i)
            payload = None
        elif wire == 2:
            size, i = varint(data, i)
            payload = data[i:i + size]
            i += size
        elif wire == 5:
            i += 4
            payload = None
        elif wire == 1:
            i += 8
            payload = None
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield number, wire, data[start:i], payload


def tag_of(entry: bytes) -> str:
    for number, wire, _, payload in fields(entry):
        if number == 1 and wire == 2:
            return payload.decode()
    return ""


def keep(data: bytes, tags: set[str]) -> bytes:
    out = bytearray()
    found = set()
    for number, wire, raw, payload in fields(data):
        if number == 1 and wire == 2 and tag_of(payload).upper() in tags:
            out += raw
            found.add(tag_of(payload).upper())
    missing = tags - found
    if missing:
        raise SystemExit(f"tags not found: {', '.join(sorted(missing))}")
    return bytes(out)


def load(arg: str | None, url: str) -> bytes:
    if arg:
        return Path(arg).read_bytes()
    print(f"downloading {url}")
    with urllib.request.urlopen(url) as response:
        return response.read()


def main() -> None:
    args = sys.argv[1:] + [None, None]
    OUT.mkdir(exist_ok=True)
    for name, data, tags in (
        ("geoip.dat", load(args[0], GEOIP_URL), GEOIP_TAGS),
        ("geosite.dat", load(args[1], GEOSITE_URL), GEOSITE_TAGS),
    ):
        result = keep(data, tags)
        (OUT / name).write_bytes(result)
        print(f"geodata/{name}: {len(result)} bytes ({', '.join(sorted(tags))})")


if __name__ == "__main__":
    main()
