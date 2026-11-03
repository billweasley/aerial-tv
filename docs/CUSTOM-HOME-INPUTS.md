# Custom home shelf

This customization is based on upstream Aerial v1.76 (685890f). See [port and validation notes](PORT-1.76.md).
The original project and its MIT license are retained.

A single source shelf sits directly above the app shelf, including in the single-row layout.
Both shelves use the same glass renderer and aligned outer edges. Source cards use the app
card height, including when previews are enabled; there is no redundant shelf title.
Choose and order up to three sources through the existing TV or phone
settings. Each source opens directly; the final action opens the full list. With no available
pinned sources, the shelf and its glass plate disappear and directional navigation skips them.
Long-press a source to move it or add a note. Notes preserve its device name. Personalization
also offers glass transparency at 0, 25, 50 and 75 percent. Google TV is available in the device
chooser on both TV and phone.

Device names come from TvInputInfo's CEC labels or a snapshot imported by an authorized computer:
`python3 tools/sync-hdmi-names.py <TV IP>`. The script runs `adb shell dumpsys hdmi_control` and
passes the snapshot to the protected ConfigReceiver. The app stores only parsed names. Its parser
reads the current device table, ignores the TV and placeholder names, and prefers playback devices
over receivers on the same port. Repeat the script after changing attached devices. A snapshot
is not live detection. HDMI splitters and switches may not forward CEC device identities.
The app does not run dumpsys, embed an ADB client, request DUMP, or open video previews to discover names.

The daily wallpaper button chooses another cached daily image. The photo screensaver shuffles
images, supports directional skipping, and gives captions 56 dp side margins and up to two lines.
The optional recommendation/live content shelf is described in [Home content](HOME-CONTENT.md);
named services and TV behavior require validation before claiming compatibility.

For compatible setups, the protected ConfigReceiver accepts `--ez capture_home_key true`.
On Android 12 and newer, this opts into consuming Home while Aerial is resumed. It stops filtering
immediately when Aerial pauses, including before another app opens or during an intentional visit
to the stock launcher. Mouse/gamepad and standby exclusions remain in effect, and volume keys
are passed through. `false` restores the upstream Home callback behavior. Android 11 and older
retain the upstream filtering policy. Home from another app may briefly show the stock launcher
while the guard brings Aerial back; keeping all of that app's keys filtered can duplicate movement
on Xiaomi firmware.

Some HyperOS versions use a separate `system/screen_saver_timeout`. The custom setup can save
its previous value in `global/aerial_previous_xiaomi_screen_saver_timeout` and set it to -1 so it
does not cover Aerial's screensaver. `tools/restore.sh` restores the saved timer if it is still -1,
including after the package was removed. This does not disable the system screensaver application.

Build and check:

```sh
./gradlew :app:assembleStandardRelease :app:assembleGuardRelease :app:testStandardReleaseUnitTest
```

Use the project's normal release-install process. Build from this checkout for the custom version:
upstream releases and the upstream installer's default download do not include these changes.
The signing key must match an existing installation to update it in place. Never uninstall an
existing installation or discard its data without its owner's consent. Private device settings,
signing material, tokens and household backups are excluded from this repository.

HDMI validation limits: a prior installation was followed by a report of no picture on HDMI.
A reboot recovered the picture; the cause was not established. Successful activity launch,
CEC identity, connected status and non-null framework sessions do not prove a visible picture.
Experimental dynamic SPD support remains unconfigured on the test TV; see [SPD notes](DYNAMIC-SPD.md).
