# Dynamic HDMI source descriptions

Experimental support is included in the v1.76 custom build. No RX endpoint has been verified or
configured on the test TV. Empty configuration performs no driver reads. Installation, compilation
and simulated console switches do not establish automatic device identification on real hardware.

Parser and integration tests cover same-port PS5 to Switch changes, invalid packets, loss of
signal, unconfigured ports and endpoint validation. Actual switching still needs hardware validation.

`HdmiSpd` parses the HDMI Source Product Description: 8-byte vendor, 16-byte product, source byte.
Full packets require type 0x83, version 1, length 25 and a valid checksum. Binary and strict hex
are supported. Headerless payloads need an explicit format selection. EDID is not a source name.
See [Linux HDMI implementation](https://github.com/torvalds/linux/blob/master/drivers/video/hdmi.c).

The `mediatek` adapter parses only the SPD section of the published MediaTek RX `pkt_all` output:
`Dump SPD , Count = 1`, three HB bytes, then PB bytes, including checksum. Other packet types,
missing SPD, multiple SPD records and corrupt packets do not produce a device identity.
The RX format and read-only attribute are documented in the manufacturer's kernel source:
[TCLOpenSource mt9653](https://github.com/TCLOpenSource/mt9653),
`drivers/media/platform/mtk-srccap/merak/mtk-srccap-hdmirx/mtk_srccap_hdmirx_sysfs.c`,
functions `_dump_pkt`, `pkt_all_show`, and `HDMIRX_P_ATTR_RO(pkt_all)`.
This source is evidence of a driver interface, not evidence that this Xiaomi exposes it.

An authorized computer supplies a mapping from each exact TvInputInfo id to its verified RX
attribute using ConfigReceiver `--es spd_sources '<JSON>'`. Each entry is
`{"path":"<verified absolute RX path>","format":"frame|payload|mediatek"}`.
Do not guess that a driver's port 0 is a particular labeled HDMI port. Only `/sys/` and `/proc/`
attributes are accepted; traversal and other roots are rejected, including after canonicalization.
There are no default paths, device-node ioctls, vendor Binder transaction numbers, root operations,
or runtime shell/ADB connections. No writes to the RX driver are performed.

While Home is resumed, configured inputs refresh every two seconds and on framework input
callbacks. Returning from HDMI immediately refreshes. No polling occurs while Home is paused.
Reads run off the UI thread, are bounded to 16 KiB and wait at most 150 ms each. A stuck read holds
one worker and does not cause more reads to queue behind it. No identity is persisted by SPD.

A configured SPD input uses its fresh product name instead of a stored CEC snapshot or manual
device type. An unavailable, malformed, disconnected or timed-out read falls back to the port
label. Owner notes and custom pictures remain saved. Unconfigured inputs retain existing behavior.
The underlying driver could itself retain stale data: checking signal loss and actual switching
on the target TV is still required. Third-party file permissions may make the adapter unusable;
the app does not change file permissions to work around that.

`--ez spd_probe true` reports the most recent per-input status after a refresh. `--es spd_sources
'{}'` removes the mapping and stops polling. The diagnostics and phone state expose SPD status
and raw vendor/product fields to distinguish a real reading from another name source.

Required device verification, with permission to use the TV: read and identify the
real RX node without switching any input; confirm app access; map the correct port; compare PS5,
Switch and signal loss; return Home and verify text, icon, note, Home key and HDMI video behavior.
Do not install another application, enable debugging interfaces, or alter a source to discover SPD.
