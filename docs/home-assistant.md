# Home Assistant

Halo TV can act as a notifier and as a TV in Home Assistant: a message on the screen (text, a photo, and
a voice that reads it out), a `media_player` entity for the television itself, and a handful of commands —
pause, screen off, screen on.

Nothing new is opened on the network. It all goes through the launcher's own small HTTP bridge, on port
8765, answered by the launcher's own process.

*中文速览：[home-assistant.zh-CN.md](home-assistant.zh-CN.md).*

---

## What you get

| | |
|---|---|
| `notify.mobile_app_halo_tv` | A message on the TV: `message`, `title`, and a `data:` dictionary with a picture, a voice and a duration. Named like the companion app's, so an automation you already have for a phone works with one word changed. |
| `media_player.halo_tv` | The TV. **Off** means the screen is dark, so `turn_off` is "screen off" and `turn_on` brings it back. Also play, pause, stop, volume, and what is playing (title, artist, artwork, app). |
| `halo_tv.command` | One action at a time — `pause`, `screen_off`, `say` — for scripts that want to be explicit. |

The `media_player` also carries, as attributes, whether a message is showing, how the screen is off
(`dim` or `sleep`), and which app is playing.

---

## Before you start

1. **Halo TV 2.4 or newer**, with this feature, installed on the TV: `./install.sh --home-only`.
2. **The two ADB grants** `install.sh` now makes. Both are optional — without them everything still
   answers, just with less reach:

   | Grant | What it buys | Without it |
   |---|---|---|
   | `appops set com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW allow` | A message can be drawn over another app (a film, Netflix) and can be started while Halo TV is in the background. | Messages only appear while Halo TV is on screen. |
   | `cmd notification allow_listener com.kartikmodi.halo.home/.HaMediaListener` | Reading the media session: what is playing, and a clean pause. | Pause falls back to sending a media key, and no title/artist. |

   Check them with `./tools/tv.py 'appops get com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW'` and
   `./tools/tv.py 'cmd notification allowed_listeners'`.

   To grant them by hand:

   ```bash
   TV_HOST=<tv-ip> tools/venv/bin/python tools/tv.py 'appops set com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW allow'
   TV_HOST=<tv-ip> tools/venv/bin/python tools/tv.py 'cmd notification allow_listener com.kartikmodi.halo.home/.HaMediaListener'
   ```

3. **Know the TV's address** — `http://<tv-ip>:8765`.

---

## Install the integration

**With HACS.** HACS → Integrations → ⋮ → **Custom repositories** →
`https://github.com/nesror/halo-tv`, category **Integration** → add, then search **Halo TV** and
download it → **restart Home Assistant**. HACS takes the integration from the latest release
(`halo_tv.zip`, named in `hacs.json`), so it can update itself like any other HACS integration.

**By hand.** The component lives in this repository, under `homeassistant/custom_components/halo_tv`.

```bash
# on the machine running Home Assistant
cp -r homeassistant/custom_components/halo_tv <config>/custom_components/
```

`<config>` is the folder holding `configuration.yaml` (often `/config`, or `~/.homeassistant`).
Then **restart Home Assistant**.

**Settings → Devices & services → Add integration → Halo TV**, and paste the address. Either
`192.168.1.42` or the whole `http://192.168.1.42:8765` works; the port defaults to 8765.

You end up with one device holding two entities: the TV, and the notifier.

> The integration needs Home Assistant 2024.7 or newer.

---

## Messages

### The plain one

```yaml
action: notify.mobile_app_halo_tv
data:
  title: Doorbell
  message: Someone is at the front door
```

Twelve seconds on screen, then it goes away. Any key on the remote takes it away sooner.

### With a photo

```yaml
action: notify.mobile_app_halo_tv
data:
  title: Doorbell
  message: Someone is at the front door
  data:
    image: http://homeassistant.local:8123/local/doorbell.jpg
    duration: 25
```

`image` takes an `http(s)` address, a `data:` URI, or a path on the TV. The TV fetches it itself, so the
address must be reachable **from the TV**, not from your phone. Files in Home Assistant's `config/www`
are at `/local/...`.

### With a voice

```yaml
action: notify.mobile_app_halo_tv
data:
  message: Dinner is ready
  data:
    tts: true
    language: en-GB
```

The TV's own text-to-speech engine reads it out, ducking whatever is playing rather than stopping it.
`language` is optional; without it the TV's own language is used. If the message should be *only* spoken,
send an empty `message` with a `title`, or use `action: halo_tv.command` with `action: say`.

### A camera snapshot, live

`camera` entities hand out a self-authenticating URL, which is exactly what the TV needs:

```yaml
action: notify.mobile_app_halo_tv
data:
  title: Front door
  message: Someone rang the bell
  data:
    image: >-
      http://homeassistant.local:8123{{ state_attr('camera.front_door', 'entity_picture') }}
    tts: true
    duration: 30
```

### Commands through notify

Any of these can ride along with a message, or be sent on their own:

```yaml
action: notify.mobile_app_halo_tv
data:
  message: Paused for you
  data:
    command: pause
```

A message that *is* a command can also be written as one line, which is handy in a template:

```yaml
action: notify.mobile_app_halo_tv
data:
  message: /screen_off
```

---

## The TV entity

`media_player.halo_tv` behaves like a television:

| Home Assistant | On the TV |
|---|---|
| `turn_off` | Screen off — a real sleep where the TV allows it, otherwise a black screen at zero brightness, after which the TV's own sleep timer finishes the job. **Any key on the remote brings the picture back.** |
| `turn_on` | Screen on, woken from either kind of off. |
| `play` / `pause` / `stop` | Whatever is playing: the media session of YouTube, Netflix, Plex... |
| `volume_set` / `volume_mute` | The TV's music volume. |
| state `off` | The screen is dark. |
| state `playing` / `paused` / `idle` | Something is playing / paused / the TV is on but quiet. |

```yaml
# Screen off at midnight, back on at seven
automation:
  - alias: TV sleeps at night
    triggers:
      - trigger: time
        at: "00:00:00"
    actions:
      - action: media_player.turn_off
        target:
          entity_id: media_player.halo_tv
  - alias: TV wakes in the morning
    triggers:
      - trigger: time
        at: "07:00:00"
    actions:
      - action: media_player.turn_on
        target:
          entity_id: media_player.halo_tv
```

```yaml
# Pause the film when someone rings the bell
automation:
  - alias: Doorbell pauses the TV
    triggers:
      - trigger: state
        entity_id: binary_sensor.doorbell
        to: "on"
    actions:
      - action: media_player.media_pause
        target:
          entity_id: media_player.halo_tv
```

---

## Commands

`halo_tv.command` is the explicit form, for scripts. Every action the TV knows:

| Action | What it does |
|---|---|
| `pause` / `play` / `play_pause` / `stop` | Playback. |
| `next` / `prev` | The next or previous track, in the app that is playing. |
| `screen_off` / `screen_on` / `screen_toggle` | The screen. |
| `home` | Opens the launcher. |
| `dismiss` | Takes a message off the screen. |
| `say` | Reads `text` out loud, with no message on screen. |
| `stop_speech` | Stops talking. |
| `volume_set` | `level: 0.4` (0–1) or `volume: 40` (0–100). |
| `volume_up` / `volume_down` / `mute` / `unmute` | Volume. |
| `notify` | The same fields as the notify entity: `title`, `message`, `image`, `tts`, `duration`. |

```yaml
action: halo_tv.command
data:
  action: say
  text: The washing machine has finished
  language: en-GB
```

```yaml
action: halo_tv.command
data:
  action: notify
  title: Laundry
  message: The washing machine has finished
  image: http://homeassistant.local:8123/local/washer.png
  tts: true
  duration: 20
```

With more than one TV, add `target: device_id: ...` to the call. The service answers with the TV's state
(`response_variable`), which is useful in a script that wants to check what happened.

---

## How it works

Four endpoints on the TV's existing port. Any of them can be used from Node-RED, a shell script or
another system — no Home Assistant needed.

```bash
curl http://192.168.1.42:8765/api/ha/info     # what the TV is
curl http://192.168.1.42:8765/api/ha/state    # what it is doing now

curl -X POST http://192.168.1.42:8765/api/ha/notify \
  -d '{"title":"Doorbell","message":"Someone is at the door","image":"http://…/doorbell.jpg","tts":true,"duration":20}'

curl -X POST http://192.168.1.42:8765/api/ha/command -d '{"action":"screen_off"}'
```

`state` answers with `screen` (`on`/`off`), `screen_mode` (`awake`/`dim`/`sleep`), `message`, `media`
(`title`, `artist`, `artwork`, `app`, `app_label`, `playing`, `paused`, `position`, `duration`), `volume`,
`volume_level`, `volume_max`, `muted` and `overlay`.

There is no authentication: anything on your Wi-Fi can talk to the TV.
Keep the TV on a network you trust, or block port 8765 at the router if that matters to you.

---

## Troubleshooting

| | |
|---|---|
| **"Cannot reach the TV"** while adding it | The address must be the TV's, and Halo TV must be running on it. Open `http://<tv-ip>:8765` in a browser on the same Wi-Fi; if that does not load, the TV is asleep or off the network. |
| **Messages only appear while Halo TV is on screen** | The overlay grant is missing. Run the `appops set … SYSTEM_ALERT_WINDOW allow` line above. |
| **Pause does nothing in Netflix** | The notification-listener grant is missing, so the TV cannot see the media session; it then falls back to a media key, which a few apps ignore. Grant it and pause again. |
| **No title or artwork shown** | Same grant — titles and artwork come from the media session, so nothing is shown without it. |
| **The picture never appears** | The TV could not fetch the URL. It must be reachable from the TV, and a phone on the same Wi-Fi is not proof. Try `http://<ha-ip>:8123/local/test.jpg` in a browser *on the TV*. |
| **The TV does not really sleep, it just goes black** | Expected on most TVs: `goToSleep` needs a system app. The black window releases the "stay awake" flag, so the TV's own screensaver and sleep timer take over. |
| **The screen will not come back** | Press any key on the remote: the black window closes on any key, by design, and reports `screen: on` again. |
| **A message is too long** | Bodies over 12 MB are refused; images over 8 MB are ignored. That is a very large photo — resize it. |

---

## Remove

```bash
rm -rf <config>/custom_components/halo_tv
```

Then restart and delete the integration. The TV keeps working as before; nothing on it changes.
