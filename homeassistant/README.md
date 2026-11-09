# Halo TV for Home Assistant

A custom integration that turns a TV running Halo TV into a notifier and a television in Home
Assistant.

* `notify.mobile_app_halo_tv` — a message on the screen: text, a picture, a voice reading it out
* `media_player.halo_tv` — the TV: on/off (screen on/off), play/pause, volume, what is playing
* `halo_tv.command` — pause, screen off, Ambient, say, volume, one at a time

The full guide — what each field does, example automations, the HTTP API behind it, and a
troubleshooting table — is in [../docs/home-assistant.md](../docs/home-assistant.md)
(中文速览: [../docs/home-assistant.zh-CN.md](../docs/home-assistant.zh-CN.md)).

## Install

```bash
cp -r custom_components/halo_tv <config>/custom_components/
```

`<config>` is the folder with `configuration.yaml` in it (often `/config`). Restart Home Assistant,
then **Settings → Devices & services → Add integration → Halo TV** and give it the TV's address —
the same `http://<tv-ip>:8765` the phone remote uses.

Needs Halo TV 2.4 or newer on the TV (`./install.sh --home-only`), and Home Assistant 2024.7 or newer.

## What is in here

```
custom_components/halo_tv/
  __init__.py         setup, the halo_tv.command service
  api.py              the four HTTP calls the TV answers
  config_flow.py      the "add integration" form
  coordinator.py      polling every few seconds
  entity.py           the device both entities hang off
  media_player.py     media_player.halo_tv
  notify_service.py   notify.mobile_app_halo_tv
tests/
  fake_tv.py          a stand-in TV, for working on this without one
  test_halo_tv.py     config flow, names, payloads
examples/
  automations.yaml    copy-paste automations
```

## Working on it

There is no need for a TV: `tests/fake_tv.py` answers the same four endpoints and remembers what it
was sent.

```bash
python3 tests/fake_tv.py --port 18765     # then add the integration at 127.0.0.1:18765
```

The tests use Home Assistant's own test harness:

```bash
pip install pytest-homeassistant-custom-component
pytest tests -q
```
