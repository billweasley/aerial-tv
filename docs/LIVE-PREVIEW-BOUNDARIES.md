# Live preview boundary audit, 2026-11-07

Owner reports: Home returns to Aerial while mytv keeps sounding despite preview sound off;
Back/Exit in mytv leaves preview unavailable; channel switching still shows delayed history.
This audit precedes runtime changes. Xiaomi ADB was unreachable; audio ownership and the actual
installed build have not been verified in this pass.

## Evidence from the current custom branch

- `HomeContent.appOpened` persists the launch wall-clock time. `MytvContent.followRecent`
  rejects any engagement timestamp older than that value, including an otherwise valid last
  channel after ordinary app exit. There is no expiry or completed-return transition for the gate.
- Watch Next supplies published history, not an authoritative immediate player channel. Observing
  updates cannot make mytv publish sooner. A timestamp is not proof that the channel is current.
- `contentDismissedFor` excludes the app from `contentEnabled` after one playback failure.
  That also cancels the update subscription. A subsequent corrected provider record cannot recover
  the preview until focus, settings, or lifecycle clears the latch.
- Aerial sets its own ExoPlayer volume to zero for muted preview and does not request audio focus
  then. This does not silence mytv's separate background player. Attribution requires playback
  UID/audio-session evidence on the TV; background audio is a hypothesis until measured.
- Source label sizes are 14sp / 11sp, with 12sp for All inputs. Increase legibility while retaining
  app-card height and accommodating four-, five-, and six-column layouts.

## Required transitions and acceptance cases

| Transition | Intended behavior | Evidence needed |
| --- | --- | --- |
| Aerial to mytv | Stop preview decoder/audio before the app starts | Decoder and playback ownership |
| Change channel, then Home | Clear old session; resolve freshly published channel; wallpaper while uncertain | Channel identity and publish times |
| Home while mytv keeps playing | Attribute sound before enabling another player; muted Aerial produces no audio | Active playback UIDs and app background-play setting |
| Back/Exit in mytv | Last confirmed channel remains eligible even if the app process exits | Provider record before/after exit |
| No usable channel record | Normal background, no guessed first channel | Missing/ambiguous data cases |
| Stream fails/times out | Restore wallpaper; retain ability to observe updates and retry with bounded backoff | Failed URL followed by corrected channel/stream |
| Rapid return/focus/channel changes | Ignore callbacks from previous sessions and surfaces | Repeated remote transitions |
| Preview disabled / screen saver / standby | Release preview player and observers | Playback ownership and lifecycle |

## Implementation direction after attribution

Separate selected-channel resolution from stream-player state. A stream failure must not stop
channel observation; use cancellable bounded retry rather than permanent per-app dismissal.
Freshness checks must apply to a return/session with an explicit completion and timeout, rather
than permanently invalidating persistent last-channel history. Keep confirmed history distinct
from a newly confirmed current channel. Guard all callbacks with session and stream identity.
Maintain normal wallpaper until the replacement renders. Muted preview should disable its audio
track as well as set volume zero. If sound is from mytv, handle its documented background-play
behavior separately; do not change global TV volume or force-stop other apps as a substitute.

Real-TV reproduction must include both Home-with-app-running and Back/Exit, with sound off/on,
rapid channel changes, an invalid stream that later recovers, and preview disable/re-enable.
No fix or successful real-TV validation is claimed by this audit.

## Implemented follow-up: 1.76-inputs.9 (108)

The defects above describe version 107. This follow-up bounds the launch-time gate to the first
four seconds of reading on return, then permits uniquely matched published last-channel history.
If publication remains delayed, this is still last published history, not verified current playback.
Provider notifications remain active; mytv also gets a two-second polling fallback after initial
one-second retries. Recommendation polling remains at one minute.

Stream failure now suppresses only the live surface, not content loading. It restores the normal
wallpaper and schedules retries after 3, 10, then 30 seconds, capped at 30. A newly resolved stream
can recover immediately. Pausing, disabling, or changing selection cancels the relevant work.
A failed live stream is not rendered as a recommendation card. Decoder readiness is remembered per
URL/session to avoid temporarily reusing the previous decoder-ready flag.

Muted Aerial previews now disable the audio track as well as setting volume zero, both at creation
and on toggle. This does not claim to silence or control mytv's independent background player.
Attribution and ordinary Back/Exit recovery still require the TV. Source labels increase to 18sp,
notes to 13sp, and All inputs to 14sp, with the existing card height retained.

Both release flavors built successfully and all 194 standard unit tests passed. The new regression
proves the initial freshness wait expires so last published history remains usable after exit.
Signatures match the previous custom signer. No TV installation or real-TV validation in this pass.

## Live observation, 2026-11-08

The owner switched from Inner Mongolia Satellite TV to Hunan Satellite TV. A screenshot confirmed
Hunan video in mytv's foreground player while three consecutive protected Watch Next probes still
returned Inner Mongolia. A later probe returned Hunan, consistent with the owner's later desktop
update. Thus this instance includes publisher delay; polling frequency alone cannot eliminate it.
The source currently publishes one Watch Next record and no preview-program rows. There is no
active mytv MediaSession in the captured system report. Its web API refused connections. Installed
manifest inspection shows SPContentProvider, RecommendationBroadcastReceiver and the recommendation
job service are not exported; another app cannot use them to request immediate current state.
The exported voice service has no bound interface. No private provider or receiver is bypassed.

Version 109 adds monotonic timing for observed record changes and preview preparation-to-visible
first frame. The record age measures when Aerial first observed a changed record, not the unknown
channel-switch time. Protected diagnostics include the publisher's engagement timestamp. Actual
stream URLs and the full channel list are not added to diagnostics. Live preview startup buffering
uses 300ms rather than Media3's default startup requirement; rebuffering retains 1000ms and the
steady buffer range is 5–15s. This reduces the local playback part of latency; it does not claim
to accelerate the source publisher or guarantee the current channel before it is published.

## Installed-code inspection and read-only direct state, version 110

The installed mytv is 3.0.0.235. Its current public repository says newer source is no longer
published; older upstream source was read only as background. Installed bytecode was inspected
locally to verify this exact build. No decompiled code, APK, settings, credentials, or signing keys
are committed here.

`DefaultChannelRecommendationJobService.onStartJob` deletes/reinserts Watch Next using the saved
last-play channel. The scheduler calls `setPeriodic(900000L)`: a 15-minute job, not a per-channel
notification. The internal channel-selection path stores `IPTV_CHANNEL_LAST_PLAY` immediately.
`GET /api/configs` serializes that value as `iptvChannelLastPlay`, including `name` and `standardName`.
Thus Watch Next delay is explained by actual publisher code, rather than a guessed polling interval.

Aerial now tries that existing loopback GET once per content refresh, even with an imported playlist,
and resolves its channel against the playlist. Direct state outranks Watch Next; a known direct
channel that cannot be uniquely matched yields normal wallpaper instead of an older historical
channel. If the web API or field is unavailable, the existing bounded Watch Next fallback remains.
Full configs are used in memory only and are never saved to Aerial preferences or sent off-device.

The web service starts when mytv MainActivity resumes and stops when that activity is destroyed.
Therefore direct reads cannot be promised after ordinary exit or when that service is stopped.
Aerial does not open mytv in the background or bypass its unexported providers/receivers. Reliable
post-exit immediate synchronization may still require a publisher-side change. The TV became
unreachable before the new direct endpoint path could be exercised; fixture tests do not constitute
real-TV validation. Version 110 also includes the uninstalled version 109 startup-buffer and timing
changes described above. No mytv software or TV settings were changed.

Version 110: both release builds and 196 standard unit tests pass. Regression fixtures verify direct
Hunan state outranks historical Inner Mongolia, unmatchable direct state does not play historical
content, and missing API state uses the existing bounded fallback. The installed signer is retained.
This build has not been installed or exercised against the TV web endpoint.

Source availability: https://github.com/mytv-android/mytv-android (current README); older upstream
https://github.com/yaoxieyoulei/mytv-android/tree/feature/ui was inspected as historical context only.

## Xiaomi verification of version 110, 2026-11-08

Version 110 / 1.76-inputs.11 was installed in place on Xiaomi MFTR0 and compiled with speed.
The existing mytv MainActivity was opened normally, activating its existing web service; no mytv
APK, source subscription, or configuration was changed. A direct GET returned live channel data.

Using the remote Down/Select sequence, the live player was switched to Shandong Satellite TV,
then Home was pressed. Direct GET returned Shandong repeatedly at 2/4/6/8 seconds after Home,
and the desktop screenshot showed the Shandong logo and moving program. The measured preview
prepare-to-first-visible-frame time was 1301ms; this is not total Home-to-preview latency. A second
remote switch yielded Jiangsu Satellite TV and the desktop screenshot also showed Jiangsu. Watch
Next still named Sichuan, demonstrating that these previews followed direct state instead of the
stale history record. One earlier API request timed out; transient service availability remains a
fallback case. Ordinary Back/Exit with the web service destroyed is not validated by these tests.

The separate audio issue is confirmed: while Aerial was focused and preview sound was off, the
active AudioTrack belonged to mytv's `:player` process. No active Aerial AudioTrack appeared in that
snapshot. Read-only configuration reported appBackgroundPlayEnable=false and appPipEnable=false.
Thus a second mute change in Aerial cannot alone claim to fix that background player. Settings were
not changed, and no audio-focus workaround or publisher patch was tested in this pass.

Screenshots and raw package/audio diagnostics are private local artifacts, not committed here.
