# Optional Home content

Custom build `1.76-inputs.8` (versionCode 107) adds three persistent options in Personalize:

- Disable updates: on by default for this custom build. Stops version checks, retry timers,
  update notices and new downloads/install requests. Turning it off enables the upstream
  updater again. This does not disable content refreshes or same-version compilation repair.
- Daily picture button: shown in Personalize only with a daily wallpaper selected. Its Home
  button also requires a daily selection, the option enabled, and another cached daily image.
  It cannot change a photo, built-in image, aerial film or HDMI wallpaper into a daily image.
- Video and music recommendations: off by default, at most three cards.
- Live TV background preview: an independent toggle, muted by default. Per-app switches and
  content sources are on the authenticated phone settings page.
- Pinned inputs together with two app rows automatically turn recommendations off and display
  a notice. Neither condition alone does this. Live background preview remains independent.

## Content, rather than placeholder cards

Apps on Home's first row can supply a Top Shelf above the pinned input shelf. Focus settles for
450 ms before loading. Recommendations are limited to three equal-width cards; a lone card keeps
the same width. Inputs remain available below. Up enters the sources, then the recommendation cards;
Down and Back return toward the apps. Video and music recommendations are refreshed every minute
while the relevant app is selected and Aerial is resumed, and on focus/return. Playback and refresh
stop while another app opens or the screensaver starts.

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

The live preview fills the wallpaper behind Home, preserves aspect ratio, and has no channel
list or channel buttons on Home. Inputs stay visible. MPEG-TS/progressive HTTP streams and HLS use
Media3. An initial load or later buffering period exceeding eight seconds, a playback error, or
end of stream restores the ordinary background. Moving wallpaper releases its decoder for the
preview and resumes when the preview exits. Missing content never chooses an arbitrary first channel.

For mytv-android `com.github.mytv.android`, the optional adapter reads its existing loopback web API
on port 10591. The bundled web panel in the installed 3.0.0.235 APK establishes these read routes:
`GET /api/configs` and `GET /api/file/content?path=...`. The adapter uses only the configured active
HTTP subscription or selected local playlist. It never changes settings or opens the other app in
the background. The web API must already be running. For mytv, the playlist is matched to the latest published
Watch Next record. A unique channel-name or stream match is required; absent or ambiguous records
return no preview. The adapter reads Watch Next separately so recommendations cannot fill its quota.
A snapshot retains all playlist channels up to 512, not just the first twelve.

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
three recommendation cards are shown; playlists retain up to 512 channels for recent-channel matching. This is an owner-configured feed format, not a claim that any
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

## Xiaomi live verification, 1.76-inputs.5

VersionCode 104 was installed in place on Xiaomi MFTR0 (Android 14) and compiled with `speed`.
The existing mytv source was SMB. Its configuration process was frozen by the firmware, so TCP
could connect while HTTP requests received no response. Temporarily unfreezing only that process
allowed the read-only importer to resolve and import the existing 131-channel playlist. The source
app's settings were not changed, and no persistent freezer setting was changed. An imported snapshot
lets Home preview channels without depending on mytv's configuration process staying runnable.

This pass also fixed channel switching: Compose retained the AndroidView factory when a different
ExoPlayer was created, leaving the new player without a display surface. Each channel now creates
its own TextureView, and the video keeps its aspect ratio. Card Up explicitly focuses Inputs;
Down from that action returns to the selected channel.

Real screenshots confirmed Tianjin Satellite TV playback, switching to Tianjin News with distinct
news-channel content, continued changing frames after a nine-second follow-up, and the Inputs action
restoring the original pinned-source shelf with focus on its first source. Home returned to Aerial;
the guard stayed enabled. Both release flavors build and all 188 standard unit tests pass.
Other platforms' account-personalized recommendations and the TCL revision remain unverified.

For SMB import, an isolated computer-side Python environment can be prepared under the ignored
local directory: `python3 -m venv local/live-preview-env`, followed by
`local/live-preview-env/bin/python -m pip install smbprotocol`. Run the importer with that Python.

## Latest follow-up, 1.76-inputs.8

Version 106 was installed and compiled with speed on Xiaomi. Screenshots confirmed full-screen
Tianjin City playback matching mytv’s published Watch Next record, the fixed input shelf, three
Bilibili cards, a single Moonlight card occupying one third of the row, and the two-row conflict
notice. The owner subsequently reported delayed old-channel playback and occasional black flashes.
These reports supersede any assumption that version 106 completed transition/fallback validation.

Version 107 changes the source card height to the same `metrics.tileH` used by apps, removes the
source header, and keeps the aligned shelf at its original full width independently of preview
settings. Its source order, notes, maximum of three, and empty-source hiding remain intact.

Content reads now observe Android TV provider updates, retry during the first eight seconds after
return for mytv, and retain the minute refresh for video/music recommendations. Opening mytv records
a local visit timestamp; a Watch Next record older than that visit is rejected rather than playing
the previous session’s channel. If the player delays publishing or does not publish a usable recent
record, the normal background remains. This cannot discover an unpublished private channel state.

Pause/resume clears the old preview synchronously even when Compose draws no frame between lifecycle
events. A paused preview immediately hides its native texture and stops its decoder. Stream changes
reset readiness by URL/session; a rendered frame gets two composition frames before the live layer
is revealed. On return from preview, the aerial picture remains while its wallpaper player prepares,
instead of using the upstream black-until-fresh-frame path. Other wallpaper/saver transitions retain
their existing behavior.

Both release flavors build; 193 standard unit tests pass, including stale recent-record rejection,
matching beyond the first twelve playlist entries, missing/ambiguous channel fallback, and independent
Watch Next reads when recommendations fill the quota. The two importer integration tests and phone
JavaScript/five-language table checks also pass. The TV was switched off by the owner before this
follow-up: version 107 has NOT been installed or tested on either TV. In particular, physical remote
channel changes and absence of black frames still require real-TV validation. Live preview must stay
off until the owner chooses to resume testing.

## Recovery follow-up, 1.76-inputs.9

See [boundary audit and follow-up](LIVE-PREVIEW-BOUNDARIES.md). Source typography is larger while
retaining the app-card height. Last-channel freshness gating now expires four seconds after the
first return read, so exiting the live app does not permanently reject its last published channel.
Live metadata updates remain observed, with a two-second polling fallback. Playback failure falls
back to normal wallpaper but keeps metadata loading alive and retries with 3/10/30-second backoff.
Muted preview disables its own audio track. Sound from the separate background live app is still
unattributed and is not claimed fixed. Xiaomi was unreachable during this pass; no TV was updated.
