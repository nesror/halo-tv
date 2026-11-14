<p align="center"><img src="docs/banner.png" alt="Halo TV: Home and Tonight on a TV" width="100%"></p>

# Halo TV

**A new OS for any Android TV, Google TV or Fire TV.** Halo TV replaces your TV's ad-filled home screen with two things:

| | |
|---|---|
| **Home** | A liquid-glass home screen in one colour (Midnight), a slow *Waves* motion backdrop behind the glass, and clean white logos instead of app banners. No ads, no sponsored rows. |
| **Tonight** | One place to find films and series across **your** streaming apps, in **your** country: Netflix, Prime Video, Disney+, JioHotstar, Apple TV+, Hulu, BBC iPlayer or whatever you subscribe to. A genre wheel, AI picks that remember your taste, and a **Marvel and DC Hall of Heroes** listing every film and series a hero has been in. |

**This branch is tuned for an older set**: a Xiaomi MiTV3S on Android 6.0 (API 23), where the stock home
screen cannot be disabled and the firmware answers no Settings intent. Compared with upstream it

- runs from **Android 6.0 (API 23)**, with core library desugaring for `java.time` and a `Compat` class
  around the newer APIs the launcher uses;
- turns the header's gear into **this TV's own Settings** app and its input button into the vendor's
  **source popup**;
- takes **Home** over with an accessibility service (rate-limited, so a slow start cannot bounce between
  the two home screens);
- **has no Ambient screen**: the always-on clock, the phone web app, the QR code, the turntable player and
  Jarvis were removed, along with the web server they shared;
- keeps **Home Assistant**, now on its own small bridge (`HaServer`, port 8765).

It installs over Wi-Fi from your computer in one command, and it's built to be installed **by an AI coding agent** (Claude Code, Codex or ChatGPT). Give your agent this repo and it does the setup with you.

---

## Contents

- [Screenshots](#screenshots)
- [Install with an AI agent (easiest)](#install-with-an-ai-agent-easiest)
- [Install by hand](#install-by-hand)
  - [1. What you need](#1-what-you-need)
  - [2. Turn on ADB debugging on the TV](#2-turn-on-adb-debugging-on-the-tv)
  - [3. Get your keys](#3-get-your-keys)
  - [4. Run the installer](#4-run-the-installer)
  - [5. First run](#5-first-run)
- [Using Halo TV](#using-halo-tv)
- [Home Assistant](#home-assistant)
- [Viewing time](#viewing-time)
- [Customise](#customise)
  - [Country and streaming apps](#country-and-streaming-apps)
  - [Prompts for your agent](#prompts-for-your-agent)
- [Uninstall](#uninstall)
- [Troubleshooting](#troubleshooting)
- [Keys and privacy](#keys-and-privacy)
- [Project layout](#project-layout)
- [License](#license)

---

## Screenshots

All captured on a real TV running this code.

**Home.** Liquid glass, Midnight colour, Waves motion, white logos. The TONIGHT. button opens Tonight.

![Home](docs/screenshots/home.png)

**Tonight.** Genre wheel, AI picks "because you loved…", films and series from every service you have.

![Tonight](docs/screenshots/tonight.png)

**Hall of Heroes, and the whole thing in real life.**

| Marvel and DC | Hall of Heroes | Home |
|---|---|---|
| ![Marvel](docs/screenshots/real-marvel.jpg) | ![DC](docs/screenshots/real-dc.jpg) | ![Home on the TV](docs/screenshots/real-home.jpg) |

---

## Install with an AI agent (easiest)

Open **Claude Code**, **Codex**, or **ChatGPT** with a terminal/agent mode, and paste:

```text
Clone https://github.com/kartik-modi/halo-tv and install Halo TV on my TV.
Follow AGENTS.md in the repo step by step, and ask me for anything you need.
I live in <your country>. My streaming apps are <e.g. Netflix, JioHotstar, Apple TV+>.
```

Fill in the last line (or leave it out and the agent asks). The agent reads [AGENTS.md](AGENTS.md), checks your computer's tools, asks for your TV's IP address and your keys, sets Tonight up for your country and your apps, builds both apps and installs them. You only need to do two things on the TV yourself: turn on ADB debugging ([step 2](#2-turn-on-adb-debugging-on-the-tv)) and press **OK** on the "Allow USB debugging?" prompt.

---

## Install by hand

### 1. What you need

| | |
|---|---|
| **A TV** | Any Android TV or Google TV, **Android 6.0 (API 23)** and up, or a Fire TV / Fire TV Stick / Fire TV Edition television. Tested on a Xiaomi MiTV3S (Android 6.0.1) |
| **A computer** | macOS or Linux (Windows: use WSL), on the same Wi-Fi as the TV |
| **JDK 17** | macOS: `brew install openjdk@17` · Ubuntu: `sudo apt install openjdk-17-jdk` |
| **Android SDK** | macOS: `brew install --cask android-commandlinetools` · or install Android Studio. Then accept the licences once: `yes \| sdkmanager --licenses` |
| **Python 3** | Already on macOS and most Linux distributions. Used for the built-in ADB client (no `adb` install needed). |

### 2. Turn on ADB debugging on the TV

ADB debugging lets your computer install apps on the TV over Wi-Fi. You do this once.

<details open>
<summary><b>Google TV / Android TV</b> (Sony, TCL, Hisense, Philips, Xiaomi, Chromecast with Google TV, NVIDIA Shield…)</summary>

1. **Settings → System → About.**
2. Scroll to **Android TV OS build** and click it **7 times**, until it says *You are now a developer*.
3. Go back to **Settings → System → Developer options** and turn on **USB debugging**.
   On some TVs there is also **Network debugging** or **ADB over network**: turn that on too.
4. Find the TV's IP address in **Settings → Network & Internet → (your Wi-Fi)**.

</details>

<details open>
<summary><b>Fire TV</b> (Fire TV Stick, Fire TV Cube, Fire TV Edition / Omni televisions)</summary>

1. **Settings → My Fire TV** (on newer models **Settings → Device & Software**) **→ About**.
2. Click **Your TV** (or your device's name) **7 times**, until it says *You are now a developer*.

   ![Fire TV About screen](docs/screenshots/firetv-about.png)

3. Go back one screen and open **Developer Options**. Turn on **ADB debugging**.
4. Find the TV's IP address in **Settings → Network**: highlight your Wi-Fi network and press **Play/Pause**.

</details>

### 3. Get your keys

Keys are typed into the installer (hidden as you type) and saved only on your computer in `tonight/secrets.properties`, which git ignores. **Nothing secret is ever committed.**

| Key | Needed for | Where to get it | Cost |
|---|---|---|---|
| **TMDB API Read Access Token** | Tonight (required for Tonight) | [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api): create a free account, request an API key, then copy the long *API Read Access Token* | Free |
| **Anthropic API key** | Tonight's AI picks and taste memory (optional) | [console.anthropic.com/settings/keys](https://console.anthropic.com/settings/keys) | Pay as you go, a few cents a month |

### 4. Run the installer

```bash
git clone https://github.com/kartik-modi/halo-tv.git
cd halo-tv
./install.sh
```

It walks through seven steps:

1. **Checks your tools** (JDK 17, Android SDK, Python) and tells you exactly what to install if something is missing.
2. **Connects to the TV.** Enter its IP address.
3. **Asks for your keys** (step 3 above), **your country** (it suggests the TV's own) and **your streaming apps**: it lists every streaming service TMDB knows in your country, most popular first, with a `*` on the ones whose app is already on the TV. Type the numbers or names you subscribe to, or press Enter for the starred ones. Everything goes into the git-ignored `tonight/secrets.properties`.
4. **Builds** Home and Tonight.
5. **Installs Home** and makes it your home screen:
   - **Google TV / Android TV:** Halo TV becomes the real Home and the stock launcher is disabled (not deleted). If anything goes wrong it is put back automatically.
   - **Fire TV:** Fire OS doesn't let another app own Home, so the Amazon launcher stays and Halo TV's Home-button service sends the Home button to Halo TV.
   - **MIUI TV / firmware that cannot be disabled (this branch):** the stock launcher stays; `home_forward_package` in `config.xml` makes its window count as the Home button, and the same accessibility service brings Halo TV back.
6. **Installs the Waves backdrop** behind the glass.
7. **Installs Tonight.**

Useful options:

```bash
./install.sh --tv 192.168.1.50          # skip the IP prompt
./install.sh --home-only                # Home only
./install.sh --tonight-only             # Tonight only
./install.sh --keep-stock               # never disable the stock launcher (use the Home-button redirect)
./install.sh --input-key-service        # also open Halo TV's input switcher from the remote's Input button
TV_HOST=192.168.1.50 TMDB_TOKEN=... REGION=IN SERVICES="Netflix, JioHotstar, Zee5" ./install.sh --yes   # no prompts
```

Re-running it is safe: it reinstalls over the existing apps and keeps your settings.

### 5. First run

- The first time the installer connects, the TV shows **"Allow USB debugging?"**. Tick **Always allow from this computer** and choose **OK**. (If you miss it, run `./install.sh` again.)
- Press **Home** on the remote: Halo TV opens.
- Tonight builds its search index in the background over the first hour; browsing works straight away.

---

## Using Halo TV

| | |
|---|---|
| **Home** | Arrow keys move between apps; the first row is the dock. **TONIGHT.** in the top bar opens Tonight. Next to it sit **Inputs**, **Wi-Fi** and **Settings**: Settings opens this TV's own settings app, Inputs the set's source popup (see `source_popup_broadcast` and `source_rows`). Hold **OK** on Settings for Halo's own launcher settings. |
| **Tiles** | Glass tiles show a bundled logo where `glass_logos` names one, a Michroma wordmark for `wordmark:TEXT`, and otherwise the first **four characters** of the app's name, one size for every tile. |
| **Tonight** | Spin the genre wheel, open **For You** for AI picks, use search (top right), or pick **MARVEL** / **DC** for the Hall of Heroes. **Play on…** opens the title in the right app. |
| **Home Assistant** | Optional: a message on screen with a picture and a voice, and a real TV entity. See [Home Assistant](#home-assistant). |

---

## Home Assistant

Halo TV is an Android TV in [Home Assistant](https://www.home-assistant.io): it can be told to show a
message (text, a photo, and a voice that reads it out), to pause, and to go dark. The launcher itself
answers on port **8765** of the TV's Wi-Fi address — no cloud, no MQTT broker, nothing else exposed.
The component is in `homeassistant/custom_components/halo_tv`; the full guide is
[docs/home-assistant.md](docs/home-assistant.md).

**With HACS** (it is its own integration, installed from this repo's releases):

1. HACS → Integrations → ⋮ → **Custom repositories** → `https://github.com/nesror/halo-tv`, category
   **Integration**.
2. Search **Halo TV**, download it, restart Home Assistant.
3. **Settings → Devices & services → Add integration → Halo TV**.

`hacs.json` points HACS at `halo_tv.zip` on the latest release, which `tools/hacs-release.sh 2.7.0`
builds and uploads (the workflow in `.github/workflows/hacs-release.yml` does the same on a tag).

**By hand**, without HACS:

```bash
cp -r homeassistant/custom_components/halo_tv <config>/custom_components/   # then restart Home Assistant
```

Then **Settings → Devices & services → Add integration → Halo TV**, and give it the TV's address
(`192.168.1.50`, or `http://192.168.1.50:8765`). Check the TV answers first:

```bash
curl http://<tv-ip>:8765/api/ha/info
# {"app":"Halo TV","version":"2.7","model":"MiTV3S","port":8765,"features":["notify","tts",...]}
```

You get:

| | |
|---|---|
| `notify.mobile_app_halo_tv` | A message on the TV, with the same `message` / `title` / `data:` shape as the companion app on a phone. |
| `media_player.halo_tv` | The TV: on/off (screen on/off), play, pause, volume, and what is playing. |
| `halo_tv.command` | One action at a time, for scripts. |

```yaml
action: notify.mobile_app_halo_tv
data:
  title: Doorbell
  message: Someone is at the front door
  data:
    image: http://homeassistant.local:8123/local/doorbell.jpg
    tts: true
    duration: 25
```

Messages can carry a command too — `data: {command: pause}`, or a message of just `/screen_off` — so one
notification can pause the film, put a photo on screen and say what happened.

The endpoints behind it, if you would rather call them from a script:

| | |
|---|---|
| `GET /api/ha/info` | What this TV is: model, Android version, features. |
| `GET /api/ha/state` | Screen, volume, what is playing, the message card, today's viewing time. |
| `POST /api/ha/notify` | `{"message": "...", "title": "...", "image": "http://...", "tts": true, "duration": 25}` |
| `POST /api/ha/command` | `{"action": "pause"}` — `pause`, `play`, `play_pause`, `stop`, `next`, `prev`, `screen_off`, `screen_on`, `screen_toggle`, `home`, `dismiss`, `say`, `stop_speech`, `volume_set`, `volume_up`, `volume_down`, `mute`, `unmute`, `notify`, `viewing_reset`, `viewing_off`, `viewing_on` |
| `GET /api/tvtime` | Today's viewing clock, and its thresholds (see below). |

`GET /` on the same port prints this list, which makes it the quickest way to check the TV is reachable.

---

## Viewing time

A daily viewing clock is built in. It counts only time spent outside the launcher — the home screen
and message cards do not count — and needs no extra permission. By default every 30 minutes it
pauses the video, puts up a card and says that it is time to look into the distance for five
minutes; at 2 h 20 m it speaks a fixed warning ("今天看电视已满2小时，10分钟后自动关机。", the
wording need not match the real count); at 2 h 30 m the screen simply goes dark — no card, no voice. The
count resets at local midnight and survives a reboot.

The reminder is spoken through the TV's text-to-speech engine when it has one. This MiTV3S has **no
engine at all**, so those two lines are recorded (`res/raw/vo_*.wav`, 16 kHz) and stitched together at
run time by `Voice.java` — the fixed phrases plus the numbers in them, so "已30分钟，休息5分钟" and
"已1小时30分" both come out right. A TV with an engine is unaffected, and a custom `warning_text` (or a
Home Assistant message with `tts: true`) still needs an engine to be read.

Every threshold is live over HTTP, on the same port as the Home Assistant bridge:

```bash
curl http://<tv-ip>:8765/api/tvtime
curl -X POST http://<tv-ip>:8765/api/tvtime/config -d '{"daily_limit_min":120}'
curl -X POST http://<tv-ip>:8765/api/tvtime/reset
curl -X POST http://<tv-ip>:8765/api/tvtime/adjust -d '{"minutes":20}'
```

`/api/ha/state` carries a `viewing` object, and `halo_tv.command` accepts `viewing_reset`,
`viewing_off` and `viewing_on`. The full guide is
[docs/viewing-time.zh-CN.md](docs/viewing-time.zh-CN.md) (Chinese), including what a sideloaded app can
and cannot do when "turning the TV off".

---

## Customise

Home's look and dock live in [`home/app/src/main/res/values/config.xml`](home/app/src/main/res/values/config.xml). Edit it, then run `./install.sh --home-only`.

| Setting | What it does |
|---|---|
| `dock_apps` | Apps on the dock, in order. List both the Google TV and Fire TV package of an app; the missing one is skipped. |
| `hidden_apps` | Apps never shown on Home (this branch also hides MIUI TV's own launcher, `com.mitv.tvhome`). |
| `glass_home` | The liquid-glass look (on by default). |
| `glass_accent` / `glass_background` | The one colour: Midnight is `#FF5B8CFF` on `#FF040914`. Try `#FFFF5B8C` for a rose theme. |
| `glass_backdrop` | `video` (Waves behind plain glass, light), `lens` (Waves bent through the glass, heavier), or `still` (gradient only). |
| `columns` | Tiles per row (5 by default). |
| `live_tv_package` | Your TV's Live TV app for the Inputs menu (find it with `tools/tv.py "pm list packages"`). |
| `glass_logos` | `package=logo_drawable` pairs: a white logo for a tile that has no banner of its own. |
| `home_forward_package` | Firmware whose launcher cannot be disabled (`com.mitv.tvhome` on MIUI TV). Its window coming to the front is treated as the Home button. Empty on every other TV. |
| `source_popup_broadcast` | `package:action` to fire when the header's input button is pressed, for vendors that answer no input intent (MIUI TV: `com.xiaomi.mitv.settings:com.xiaomi.mitv.settings.INPUTSOURCE_POPUP`). |
| `source_rows` | `label=component` rows for Halo's own source panel, used when the vendor has no popup. |
| `settings_key_panel` | `true` opens Halo's quick settings panel for the remote's Settings key instead of the TV's settings app. |

Replace `assets/waves.mp4` with any looping 960×540 video for a different backdrop.

### Country and streaming apps

Tonight works in any country TMDB covers and with any streaming service TMDB lists there. Both are set in the git-ignored `tonight/secrets.properties`:

```properties
region=IN
services=Netflix:8,175; Amazon Prime Video:119,2100; JioHotstar:2336; Zee5:232; aha:532:ahaflix.tv
```

- **`region`**: your 2-letter country code (`US`, `GB`, `IN`, `DE`, `CA`, `AU`, `BR`...). Tonight shows only what streams there, and its search index is rebuilt automatically when it changes.
- **`services`**: one entry per app, separated by `;`, each `name:TMDB provider ids[:Android TV package]`. The order is the order of the chips and of the *"on …"* choice in "Tonight I want".

**The easy way to change either:** run the installer again and pick again.

```bash
REGION=IN ./install.sh --tonight-only                                   # new country: lists its services
SERVICES="Netflix, Apple TV, Hulu" ./install.sh --tonight-only           # same country, different apps
```

**What each service gets:**

| | Services | Opens a title |
|---|---|---|
| Built in | Netflix, Prime Video, Disney+, JioHotstar, Apple TV+, Max, Hulu, Paramount+, Peacock, ZEE5, Sony LIV, Crunchyroll, MUBI | Straight on the title (from JustWatch's link), or the app's own search |
| Any other | Whatever TMDB lists in your country (BBC iPlayer, aha, Stan, Viaplay, Crave...) | On the title when the app takes JustWatch's link, otherwise the app opens. Needs its package, see below |

**Adding a service by hand** (for example Apple TV+ in India):

1. Find its TMDB provider id for your country: `TMDB_TOKEN=... python3 tools/services.py IN --pick "apple tv"` prints the line to use.
2. For a service that isn't built in, find its package on the TV: `TV_HOST=<tv-ip> tools/venv/bin/python tools/tv.py "pm list packages" | grep -i <name>`, and add it as the third part: `BBC iPlayer:38:bbc.iplayer.android`.
3. Add the entry to `services=` and run `./install.sh --tonight-only`.

Tonight's rows and genre rules are in `tonight/app/src/main/java/com/kartikmodi/halo/tonight/`; the built-in services and their colours, packages and links are in `Services.java`.

### Prompts for your agent

Once Halo TV is installed, paste one of these into Claude Code, Codex or ChatGPT in the repo folder:

```text
Add Apple TV+ to Tonight. Follow "Country and streaming apps" in README.md: find its
TMDB provider id for my country with tools/services.py, check its package on the TV,
update services= in tonight/secrets.properties and run ./install.sh --tonight-only.
```

```text
I've moved to India. Switch Tonight to region IN, show me the streaming services
there with tools/services.py, let me pick mine, and reinstall Tonight.
```

```text
Remove Disney+ from Tonight and put JioHotstar first. Edit services= in
tonight/secrets.properties (never print the token) and run ./install.sh --tonight-only.
```

```text
Tonight opens <service> but not the title. Check whether JustWatch has a link for it
(JustWatch.java) and whether the app takes it (adb: am start -a android.intent.action.VIEW
-d <link> -p <package>). If it does, add the service to KNOWN in Services.java.
```

---

## Uninstall

```bash
./uninstall.sh --tv 192.168.1.50
```

Re-enables the stock launcher, removes Halo TV's Home-button service, and uninstalls both apps. Your TV is exactly as it was.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `could not connect to …:5555` | ADB debugging is off, the IP is wrong, or the TV is on a different network. On Google TV also check **Network debugging**. Restart the TV and try again. |
| The TV never showed "Allow USB debugging?" | It may be behind the current app: press **Home**. Or revoke old authorisations in Developer options and run the installer again. |
| Home button still opens the old home (MIUI TV) | The stock launcher cannot be disabled on this firmware. Check **Settings → Accessibility → Halo TV Home button** is on, and that `home_forward_package` matches its package (`pm list packages | grep tvhome`). |
| Home button stopped working after reinstalling | An `adb install -r` clears it from the enabled services. Re-run: `settings put secure enabled_accessibility_services <list>:com.kartikmodi.halo.home/.HomeKeyService; settings put secure accessibility_enabled 1`. |
| Home Assistant says it cannot connect | `curl http://<tv-ip>:8765/api/ha/info` on a computer on the same Wi-Fi. Nothing answers if Halo TV is not running; port 8765 must not be blocked. |
| Home button still opens the old home (Fire TV, `--keep-stock`) | the Home-button service is off. Turn on **Halo TV Home button** in **Settings → Accessibility** and run the installer again. Google TV: run `./install.sh`; it reports where Home resolves. |
| `Home currently resolves to …setupwraith` (Google TV) | Google's setup wizard is claiming Home. Use `./install.sh --keep-stock`. |
| No Waves behind the glass | Run `./install.sh --home-only` again, or set `glass_backdrop` to `still` on low-end TVs. |
| Tonight is empty | The TMDB token is missing or wrong. Fix `tonight/secrets.properties` and run `./install.sh --tonight-only`. |
| Tonight shows the wrong country's titles | Set `region=` (or run `REGION=XX ./install.sh --tonight-only`). Search rebuilds by itself, which takes a few minutes. |
| A service is missing from Tonight | Add it: [Country and streaming apps](#country-and-streaming-apps). |
| "<Service> isn't installed on this TV" | Install the app on the TV, or, for a service that isn't built in, add its package as the third part of its `services=` entry. |
| A service opens its app but not the title | That app doesn't take JustWatch's links; search for the title in the app. |
| No AI picks in Tonight | Add an Anthropic key to `tonight/secrets.properties` and run `./install.sh --tonight-only`. |
| Build fails with a licence error | `yes \| sdkmanager --licenses`, then run again. |

---

## Keys and privacy

- **No key is in this repository.** The installer writes your keys to `tonight/secrets.properties`, which is git-ignored (see [`tonight/secrets.properties.example`](tonight/secrets.properties.example)).
- Your TMDB and Anthropic keys are built into **your** copy of Tonight. Don't share the APK you build.
- The ADB key that lets your computer talk to the TV is created in `tools/adbkey/` (git-ignored).
- The launcher's HTTP bridge (port 8765) answers any device on your Wi-Fi, with no password. Keep the TV on a network you trust.

---

## Project layout

```
home/                  Home (Android app com.kartikmodi.halo.home)
  app/src/main/res/values/config.xml   dock, hidden apps, colours, look, TV quirks
  app/src/main/res/raw/vo_*.wav        the reminder phrases Voice stitches together
  java/.../HaServer.java      Home Assistant bridge on port 8765 (/api/ha/*, /api/tvtime/*)
  java/.../HaBridge.java      what that bridge answers: state, messages, commands
  java/.../TvApps.java        finds the set's own Settings and source apps
  java/.../Compat.java        the newer APIs Home uses, wrapped for API 23
  java/.../Voice.java         the recorded reminder lines, for TVs with no TTS engine
  java/.../HomeKeyService.java  Home button, for firmware that keeps its own launcher
tonight/               Tonight (Android app com.kartikmodi.halo.tonight)
  secrets.properties.example     the keys Tonight reads at build time
homeassistant/         Home Assistant custom integration (messages, media_player, commands)
  custom_components/halo_tv/   the integration itself
  tests/               its tests, plus fake_tv.py: a stand-in TV for working on it without one
tools/                 tv.py: a small ADB-over-Wi-Fi client (no adb install needed); b64push.py
assets/waves.mp4       the Waves backdrop video
install.sh             builds and installs everything
uninstall.sh           puts the TV back the way it was
AGENTS.md              step-by-step instructions for AI agents (Claude Code, Codex, ChatGPT)
```

## License

MIT. See [LICENSE](LICENSE). Bundled fonts are under the SIL Open Font License. App names, logos and film/series artwork belong to their owners; this project is not affiliated with any streaming service, Amazon, Google, Marvel or DC.
