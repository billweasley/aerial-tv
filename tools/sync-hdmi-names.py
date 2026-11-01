#!/usr/bin/env python3
"""Import current CEC device names using the owner's authorized ADB computer, without root."""
import argparse
import base64
import os
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("tv", nargs="?", default=os.environ.get("TV"), help="TV IP address or ADB serial")
args = parser.parse_args()
if not args.tv:
    parser.error("pass the TV IP address or set TV")
serial = args.tv if ":" in args.tv else args.tv + ":5555"
adb = ["adb", "-s", serial]
result = subprocess.run(adb + ["shell", "dumpsys", "hdmi_control"], check=True,
                        capture_output=True, timeout=15)
if b"mDeviceInfos:" not in result.stdout:
    raise SystemExit("The TV did not return a current CEC device table; existing names were kept.")
encoded = base64.b64encode(result.stdout).decode("ascii")
result = subprocess.run(adb + ["shell", "am", "broadcast", "-a", "app.orchard.tv.CONFIG", "-n",
                        "app.orchard.tv/.guard.ConfigReceiver", "--es", "hdmi_snapshot", encoded],
                        check=True, capture_output=True, text=True, timeout=15)
for line in result.stdout.splitlines():
    if line.startswith("Broadcast completed:"):
        print(line)
