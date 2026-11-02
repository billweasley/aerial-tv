# Optional Home content

Custom build `1.76-inputs.4` (versionCode 103) adds three persistent options in Personalize:

- Disable updates: on by default for this custom build. Stops version checks, retry timers,
  update notices and new downloads/install requests. Turning it off enables the upstream
  updater again. This does not disable content refreshes or same-version compilation repair.
- Daily picture button: shown in Personalize only with a daily wallpaper selected. Its Home
  button also requires a daily selection, the option enabled, and another cached daily image.
  It cannot change a photo, built-in image, aerial film or HDMI wallpaper into a daily image.
- App content previews: off by default. Live preview sound is separately optional and muted
  by default. Per-app switches and content sources are on the authenticated phone settings page.

## Content, rather than placeholder cards

Apps on Home's first row can supply a Top Shelf above the app shelf. Focus settles for 450 ms
before loading. Up enters the content cards; Down and Back return to the selected app. A persistent
Inputs action restores the source shelf, or opens the full input selector if no source is pinned. Content is refreshed every minute while the relevant app
is selected and Aerial is resumed; it is released when another app opens or the screensaver starts.
Turning previews off restores the ordinary wallpaper/source layout.

The default recommendation source is Android TV's `preview_program` provider. `watch_next_program`
is read separately and labeled Continue watching, not Recommendations. Only the selected package's
rows and resolvable links back into that same package are accepted. Titles and poster artwork come
from those rows; no unrelated catalog, invented recommendation or private account data is used.

This is a generic integration, not seven verified service-specific integrations. Bilibili, YouTube,
Netflix, Disney+, iQIYI, Tencent Video and music apps can work only if their installed TV version
publishes usable rows and the firmware permits Aerial to read them, or a compatible feed is configured.
Their account-personalized feeds have not been integrated or verified by this change. Missing data,
denied access, malformed feeds and unusable links all preserve the original Home layout.

Provider reads have a two-second bound and a single worker: a stuck vendor provider does not queue
more workers on every focus change. Artwork and network reads have time and size bounds. A failed
refresh removes stale content rather than continuing to present it as current recommendations.

## Live channels

Live entries contain actual channel stream URLs and names. Focusing a channel starts that channel's
muted video in the preview region. MPEG-TS/progressive HTTP streams and HLS use the existing Media3
version, with the HLS module included. The network policy permits HTTP for configured feeds,
IPTV streams and the loopback mytv service, including addresses unknown at build time. The source shelf stays until a real video frame arrives.
An initial load or later buffering period exceeding eight seconds, a playback error, or end of a live
stream returns to the ordinary Home layout. A moving wallpaper is released while live preview owns
the decoder. Leaving Home or disabling the option releases the preview player.

For mytv-android `com.github.mytv.android`, the optional adapter reads its existing loopback web API
on port 10591. The bundled web panel in the installed 3.0.0.235 APK establishes these read routes:
`GET /api/configs` and `GET /api/file/content?path=...`. The adapter uses only the configured active
HTTP subscription or selected local playlist. It never changes settings or opens the other app in
the background. The web API must already be running.

A configured SMB subscription needs its resolved channel list imported, because Aerial does not
have access to mytv's private storage or an SMB client. The computer-side helper reads the existing
subscription, including SMB when its Python environment has `smbprotocol`, and imports only the
resolved playlist into Aerial. It sends no write request to mytv and copies no SMB credentials to Aerial:

```sh
python3 tools/import-live-preview.py <TV_IP> --phone-url-file local/phone-url.txt
# Or save privately and import from the phone page:
python3 tools/import-live-preview.py <TV_IP> --output local/channels.m3u
```

The private phone URL must belong to the same TV. Keep URL files, playlists, addresses and credentials
under `local/`, never in Git. The helper can run without ADB when both existing web interfaces are
reachable. An imported file is a snapshot; re-import after the SMB channel list changes. An HTTP
subscription is fetched again automatically. Streams needing extra proprietary request headers or
DRM are not configured by this implementation.

A channel may open directly in its app only if that app exports a compatible link. Otherwise the
card opens the app's ordinary entry; it does not inject private channel-selection commands. Actual
playback and this transition still require target-TV validation.

## Optional recommendation feeds

The phone page accepts an HTTP(S) M3U/TXT channel list, a local file, or a recommendation JSON feed:

```json
{"items":[
  {"id":"episode-42","title":"Episode title","image":"https://example.com/poster.jpg",
   "intent":"https://video.example.com/episode/42"}
]}
```

Recommendation links must resolve into the configured app. A live JSON entry instead has
`"live":true` and `"stream":"https://example.com/channel.m3u8"`. Keep publisher ordering; at most
12 usable cards/channels are shown. This is an owner-configured feed format, not a claim that any
named platform provides this endpoint. Clearing a configuration restores automatic app content.

## Validation and limits

Unit tests cover provider/package filtering, recommendation versus Continue watching labels,
malformed and inaccessible content fallback, deep-link ownership, M3U/TXT/JSON parsing, selected
mytv source handling, and disabling/re-enabling update checks during an in-flight request.
The helper has local-server integration tests proving that it reads mytv without changing it and
writes only to the selected Aerial instance. Phone JavaScript and all five language tables are checked.

Both release flavors built successfully; all 188 standard unit tests and both helper integration tests
passed. Signatures match the installed custom build. Neither TV was reachable during this development pass. The TV layout, physical remote navigation,
first-frame/buffering behavior, the real SMB import and actual named-platform data are not verified.
Do not equate a release build, parser fixture or provider test with live service compatibility.

Primary interface references: [Android TV channels](https://developer.android.com/training/tv/discovery/recommendations-channel),
[Watch Next](https://developer.android.com/training/tv/discovery/watch-next), and
[Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls).
