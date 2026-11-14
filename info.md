# Halo TV

Halo TV 是一台装在电视里的 Android TV 桌面（Home），这个集成把它接进 Home Assistant：
电视上能弹一条消息（文字、图片、中文朗读），也能暂停、开屏关屏、调音量，以及看今天看了多久电视。

不需要 MQTT，不需要云，不需要额外的插件：电视上的桌面自己开着一个 HTTP 服务（端口 **8765**），
这个集成直接问它。都在同一个 Wi-Fi 里就行。

## 装好之后

一台设备，57 个实体：

| 实体 / 服务 | 作用 |
|---|---|
| `media_player.halo_tv` | 当成一台电视用（device_class 是 tv）：开关屏、播放/暂停、上一个/下一个、音量、静音、当前在播什么；所有应用都是它的音源 |
| `remote.halo_tv_remote` | 遥控器：`send_command` 一次按一个键 |
| `button.*`（19 个） | 方向、确定、返回、主页、菜单、信息、最近、播放暂停、音量±、熄屏、亮屏、待机、关消息、停止朗读、重置今日观看 |
| `switch.*`（7 个） | 屏幕、静音，以及每条观看时间规则 |
| `number.*`（7 个） | 每日上限、休息间隔、休息时长、提前提醒、消息停留秒数、熄屏延迟、音量 |
| `sensor.*`（14 个）/ `binary_sensor.*`（6 个） | 今日已看、剩余、下次休息、休息次数、已达上限、屏幕亮着、正在播放、有消息 |
| `text.halo_tv_message` | 在面板上打一句，电视就显示 |
| `notify.mobile_app_halo_tv` | 带图片和语音的消息（和手机 App 的写法一样） |
| `halo_tv.command` / `key` / `send_message` / `set_viewing` | 一次一个动作，给脚本用 |

```yaml
action: notify.mobile_app_halo_tv
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
