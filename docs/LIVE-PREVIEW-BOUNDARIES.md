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
