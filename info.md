# Halo TV

Halo TV 是一台装在电视里的 Android TV 桌面（Home），这个集成把它接进 Home Assistant：
电视上能弹一条消息（文字、图片、中文朗读），也能暂停、开屏关屏、调音量，以及看今天看了多久电视。

不需要 MQTT，不需要云，不需要额外的插件：电视上的桌面自己开着一个 HTTP 服务（端口 **8765**），
这个集成直接问它。都在同一个 Wi-Fi 里就行。

## 装好之后

| 实体 / 服务 | 作用 |
|---|---|
| `notify.halo_tv` | 在电视上弹消息：`message`、`title`、`data: {image: ..., tts: true, duration: 25}` |
| `media_player.halo_tv` | 当成一台电视用：开关屏、播放/暂停、音量、当前在播什么 |
| `halo_tv.command` | 一次一个动作：`pause`、`screen_off`、`home`、`say`、`viewing_reset` 等 |

```yaml
action: notify.halo_tv
data:
  title: 门口有人
  message: 有人在按门铃
  data:
    image: http://homeassistant.local:8123/local/doorbell.jpg
    tts: true
    duration: 25
```

## 安装

1. 电视：装好 Halo TV 桌面并开着（`curl http://<电视IP>:8765/api/ha/info` 有返回就对了）。
2. HACS → 集成 → 右上角 ⋮ → **自定义存储库** → 填 `https://github.com/nesror/halo-tv`，类型选 **Integration** → 添加。
3. 在 HACS 里搜 **Halo TV** → 下载 → 重启 Home Assistant。
4. 设置 → 设备与服务 → 添加集成 → **Halo TV** → 填电视的 IP（端口 8765）。

完整文档（端点、命令表、排错）：[docs/home-assistant.md](https://github.com/nesror/halo-tv/blob/main/docs/home-assistant.md)

## 注意

- 端口 8765 没有鉴权，同一 Wi-Fi 内谁都能访问，别把电视放到不可信的网络。
- 提醒的语音是用桌面自带的录音（电视没有 TTS 引擎），自定义文字要念出来仍需电视上装 TTS 引擎。
