#!/usr/bin/env python3
"""Read the selected mytv-android source and import its channels into Aerial's opt-in preview.

Uses only GET requests against mytv. No changes to subscriptions, SMB credentials or playback.
For an SMB source, run in a Python environment with smbprotocol (smbclient) installed.
"""
import argparse
import importlib
import json
import os
from pathlib import Path
from urllib import parse, request

LIMIT = 2 * 1024 * 1024


def read_url(url):
    with request.urlopen(url, timeout=8) as response:
        body = response.read(LIMIT + 1)
    if len(body) > LIMIT:
        raise ValueError("Channel list exceeds 2 MiB")
    return body


def current_source(config):
    current = config.get("iptvSourceCurrent")
    if isinstance(current, dict):
        return current
    sources = config.get("iptvSourceList", {}).get("value", [])
    index = config.get("iptvSourceCurrentIdx", -1)
    if not isinstance(index, int) or not 0 <= index < len(sources):
        raise ValueError("No valid current subscription; no source was guessed")
    return sources[index]


def read_source(base, source):
    url = source.get("url", "")
    if source.get("sourceType") == 1:
        return read_url(base + "/api/file/content?" + parse.urlencode({"path": url}))
    protocol = source.get("protocol")
    if protocol == "smb" or url.lower().startswith("smb://"):
        try:
            smb = importlib.import_module("smbclient")
        except ImportError:
            raise ValueError("SMB source found. Run this script with a Python environment containing smbprotocol") from None
        parsed = parse.urlsplit(url if "://" in url else "smb://" + url.lstrip("/"))
        if not parsed.hostname or not parsed.path.strip("/"):
            raise ValueError("Invalid SMB source address")
        smb.register_session(parsed.hostname, username=source.get("userName") or None,
                             password=source.get("password") or None,
                             port=int(source.get("port") or 445), connection_timeout=8)
        path = "\\\\" + parsed.hostname + parse.unquote(parsed.path).replace("/", "\\")
        with smb.open_file(path, mode="rb") as handle:
            data = handle.read(LIMIT + 1)
        if len(data) > LIMIT:
            raise ValueError("Channel list exceeds 2 MiB")
        return data
    if parse.urlsplit(url).scheme in ("http", "https"):
        return read_url(url)
    raise ValueError("Source format is not supported; existing settings were kept")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tv", help="TV IP or mytv web configuration URL")
    parser.add_argument("--phone-url-file", type=Path, help="Private file containing Aerial's phone setup URL")
    parser.add_argument("--output", type=Path, help="Save the playlist privately for import on the phone")
    args = parser.parse_args()
    if not args.phone_url_file and not args.output:
        parser.error("Specify --phone-url-file or --output")
    base = args.tv.rstrip("/") if "://" in args.tv else "http://" + args.tv + ":10591"
    try:
        config = json.loads(read_url(base + "/api/configs"))
        data = read_source(base, current_source(config))
        text = data.decode("utf-8-sig")
        if not any(line.strip().startswith(("http://", "https://")) or ",http" in line for line in text.splitlines()):
            raise ValueError("The source returned no HTTP channels")
        if args.output:
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(data)
            args.output.chmod(0o600)
        if args.phone_url_file:
            phone = args.phone_url_file.read_text().strip().rstrip("/")
            if parse.urlsplit(phone).hostname != parse.urlsplit(base).hostname:
                raise ValueError("The Aerial phone URL belongs to a different TV")
            payload = parse.urlencode({"app": "com.github.mytv.android", "feed": "", "snapshot": text}).encode()
            req = request.Request(phone + "/api/content", data=payload, headers={"Content-Type": "application/x-www-form-urlencoded"})
            result = json.loads(read_response(req))
            if not result.get("saved"):
                raise ValueError("Aerial rejected the import; existing source was kept")
        print("Channel list read; requested output/import completed. Existing mytv settings were not changed; addresses and credentials omitted.")
    except Exception as error:
        # Network/SMB exceptions may embed the URL or credentials. Never echo their messages.
        print("Import failed (" + type(error).__name__ + "). Check TV access, source format and Python SMB support.")
        return 1
    return 0


def read_response(req):
    with request.urlopen(req, timeout=8) as response:
        return response.read(8 * LIMIT)


if __name__ == "__main__":
    raise SystemExit(main())
