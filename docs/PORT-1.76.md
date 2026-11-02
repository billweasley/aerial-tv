Later revision: versionCode 103 / `1.76-inputs.4` adds optional updater/daily-picture controls and
experimental app content. Version 102's Xiaomi verification below is historical and does not validate
this new revision. See [content sources and validation limits](HOME-CONTENT.md).

# Custom port to Aerial 1.76

Upstream base: v1.76, commit 685890f. Custom version: versionCode 102 / `1.76-inputs.3`.
Standard and guard release artifacts are generated. Existing custom installations can update in
place when signed with the same key. Signing keys and private TV configuration are not published.

The source shelf, notes, source ordering/limit, Google TV choice, glass transparency, daily image
shuffle, caption width and experimental SPD changes are carried forward. Upstream mouse handling,
Home callbacks and continuous corners remain in place. See [feature notes](CUSTOM-HOME-INPUTS.md).

When no available input is pinned, Home draws neither the shelf nor its glass plate. Application
Up navigation and top-button Down navigation bypass the shelf. Closing a source menu after
unpinning its last source restores focus to an app. Input settings remain in Control Center.

On Android 12+, the optional Home capture now requires Aerial to be resumed. Previously this custom
option could keep accessibility key filtering enabled over third-party apps, causing duplicate
navigation on Xiaomi firmware. It is suspended immediately on pause, including when opening the
stock launcher. The guard remains bound and handles Home using the existing broadcast/window paths.
Mouse/gamepad exclusions, older Android behavior and separately configured CEC behavior are retained.
Home from third-party apps can still briefly expose the stock launcher.

## Validation

Both release flavors build, and all 176 standard unit tests pass. The custom signer was verified
before updating the Xiaomi MFTR0 in place. The second TV was not tested with this revision.

On Xiaomi, unpinning every source removed the shelf and glass plate; original pins were restored.
An intentional stock-launcher visit left the guard bound and KeyboardInterceptor absent. A single
Down moved one row, and Home returned to Aerial.

Version 102 was installed and compiled with `cmd package compile -m speed`. Opening 云视听小电视
left the guard bound with KeyboardInterceptor absent. One Right press through a temporary uinput
keyboard moved focus from 精选 to its immediate neighbor 推荐, without skipping a tab. This checks
the hardware input path; physical remote confirmation remains useful. A subsequent Home press
returned to Aerial, where KeyboardInterceptor was restored. The enabled guard and version 102
were confirmed; the system HOME still resolves to PatchWall as required by Xiaomi firmware.

## Startup latency remains unresolved

The custom early-boot path can take over a positively identified PatchWall front before the normal
settle timer when user storage is unlocked, Home capture is enabled, and no boot target/profile delay
is configured. Unknown fronts and HDMI players retain protection. Unlock setup work runs off the
main thread. This has not demonstrated a startup speed improvement on the test TV.

In the version-101 cold boot trace, PatchWall BootActivity displayed at 11:58:34.700 and Aerial's
wm_activity_launch_time was 11:58:40.786: a 6.086-second gap. A version-99 sample was 4.992 seconds.
The boots followed different unlock paths, so these samples do not establish a general regression
or improvement. Firmware scheduling before a third-party process can run also contributes to the
delay. No claim of eliminating the native startup screen is made.

The experimental SPD driver endpoint remains unconfigured and unverified. This port does not
establish automatic device identification. No system launcher or other system app is disabled.
