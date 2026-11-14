# Home Assistant 接入（中文速览）

英文完整文档见 [home-assistant.md](home-assistant.md)。

装好 Halo TV 的电视，可以直接当作 Home Assistant 里的「通知设备 + 电视」：

| 实体 / 服务 | 作用 |
|---|---|
| `notify.mobile_app_halo_tv` | 在电视上显示消息：文字、图片，可选语音朗读。参数形状与手机伴侣应用一致（`message` / `title` / `data:`）。 |
| `media_player.halo_tv` | 电视本体：开/关（= 亮屏/熄屏）、播放/暂停、音量、正在播放的内容。 |
| `halo_tv.command` | 单条指令：`pause`、`screen_off`、`say` 等，供脚本使用。 |

这三样都走电视上原本就给手机遥控器用的那个网页服务（8765 端口），不需要在电视上额外开端口或装服务。

---

## 一、装集成

1. 电视端需要 **Halo TV 2.4 或更新**，且已用本仓库的安装脚本装好：

   ```bash
   ./install.sh --home-only
   ```

   `install.sh` 现在会自动做两项 ADB 授权（可选，但没有就少一些能力）：

   | 授权 | 作用 | 没有授权时 |
   |---|---|---|
   | `appops set com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW allow` | 消息可以盖在其他应用（Netflix、播放器）之上显示，也能在 Halo TV 处于后台时弹出 | 只有在 Halo TV 前台时才会看到消息 |
   | `cmd notification allow_listener com.kartikmodi.halo.home/.HaMediaListener` | 读取媒体会话：正在播放什么，以及精确的暂停 | 暂停退化为发送媒体键，且没有标题/封面 |

   手动授权：

   ```bash
   TV_HOST=<电视IP> tools/venv/bin/python tools/tv.py 'appops set com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW allow'
   TV_HOST=<电视IP> tools/venv/bin/python tools/tv.py 'cmd notification allow_listener com.kartikmodi.halo.home/.HaMediaListener'
   ```

2. 装集成，两种方式任选：

   **用 HACS（推荐，能自动更新）**：HACS → 集成 → 右上角 ⋮ → **自定义存储库** →
   `https://github.com/nesror/halo-tv`，类型选 **Integration** → 添加 → 在 HACS 里搜 **Halo TV**
   → 下载 → 重启 Home Assistant。HACS 从最新 release 取 `halo_tv.zip`（由 `hacs.json` 指定）。

   **手动复制**：

   ```bash
   cp -r homeassistant/custom_components/halo_tv <配置目录>/custom_components/
   ```

   重启 Home Assistant → **设置 → 设备与服务 → 添加集成 → Halo TV** → 填入电视地址。

   地址填 `192.168.1.42` 或完整的 `http://192.168.1.42:8765` 都可以，端口默认 8765。

   需要 Home Assistant 2024.7 或更新版本。

---

## 二、发消息

### 纯文字

```yaml
action: notify.mobile_app_halo_tv
data:
  title: 门铃
  message: 有人在门口
```

默认显示 12 秒，之后自动消失；按遥控器任意键可以提前关掉。

### 带图片

```yaml
action: notify.mobile_app_halo_tv
data:
  title: 门铃
  message: 有人在门口
  data:
    image: http://homeassistant.local:8123/local/doorbell.jpg
    duration: 25
```

`image` 支持 http(s) 地址、`data:` URI，或电视本地路径。**图片是电视自己去下载的**，所以这个地址
必须电视能访问到（手机能打开不代表电视能）。放在 Home Assistant 的 `config/www` 里的文件，地址是 `/local/...`。

### 真·摄像头快照

`camera` 实体的 `entity_picture` 自带鉴权参数，正好适合电视直接抓：

```yaml
action: notify.mobile_app_halo_tv
data:
  title: 门口
  message: 有人按门铃
  data:
    image: >-
      http://homeassistant.local:8123{{ state_attr('camera.front_door', 'entity_picture') }}
    tts: true
    duration: 30
```

### 语音朗读

```yaml
action: notify.mobile_app_halo_tv
data:
  message: 饭好了
  data:
    tts: true
    language: zh-CN
```

用电视自带的 TTS 引擎朗读，朗读时只会把正在播放的声音压下去（ducking），不会中断播放。
`language` 可省略，省略则用电视自身语言。只想播报、不想显示文字时，可以不传 `message`，或用
`halo_tv.command` 的 `say`。

### 消息里带指令

```yaml
action: notify.mobile_app_halo_tv
data:
  message: 已为你暂停
  data:
    command: pause
```

或者把整条消息写成一条指令（方便模板拼装）：

```yaml
action: notify.mobile_app_halo_tv
data:
  message: /screen_off
```

---

## 三、电视实体

`media_player.halo_tv` 就是一个电视机：

| Home Assistant 调用 | 电视上的效果 |
|---|---|
| `turn_off` | 熄屏。电视允许时会真正休眠；否则用全黑零亮度窗口顶替，随后电视自己的睡眠定时器接管。**按遥控器任意键即可恢复画面。** |
| `turn_on` | 亮屏，两种熄屏方式都能唤醒。 |
| `play` / `pause` / `stop` | 控制当前播放：氛围屏自己的播放器，或 YouTube / Netflix / Plex 等应用的媒体会话。 |
| `volume_set` / `volume_mute` | 电视的音乐音量。 |
| 状态 `off` | 屏幕是黑的。 |
| 状态 `playing` / `paused` / `idle` | 正在播放 / 已暂停 / 开着但没在播。 |

```yaml
# 半夜熄屏，早上亮屏
- alias: 电视夜间熄屏
  triggers:
    - trigger: time
      at: "00:00:00"
  actions:
    - action: media_player.turn_off
      target:
        entity_id: media_player.halo_tv
```

```yaml
# 门铃响时暂停电影
- alias: 门铃暂停电视
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

## 四、指令一览

`halo_tv.command` 的 `action`：

| 指令 | 作用 |
|---|---|
| `pause` / `play` / `play_pause` / `stop` | 播放控制 |
| `next` / `prev` | 氛围屏的上一曲 / 下一曲 |
| `screen_off` / `screen_on` / `screen_toggle` | 熄屏 / 亮屏 / 切换 |
| `home` | 回到桌面 |
| `dismiss` | 关掉当前消息 |
| `say` | 只朗读 `text`，不显示消息 |
| `stop_speech` | 停止朗读 |
| `volume_set` | `level: 0.4`（0–1）或 `volume: 40`（0–100） |
| `volume_up` / `volume_down` / `mute` / `unmute` | 音量 |
| `notify` | 与 notify 实体相同的字段：`title`、`message`、`image`、`tts`、`duration` |

```yaml
action: halo_tv.command
data:
  action: say
  text: 洗衣机洗好了
  language: zh-CN
```

有多台电视时，加上 `target: device_id: ...`。该服务会把电视最新状态作为响应返回
（配合 `response_variable` 使用）。

---

## 五、协议（不依赖 Home Assistant）

电视上就是四个 HTTP 接口，Node-RED、shell 脚本或别的系统都能直接用：

```bash
curl http://192.168.1.42:8765/api/ha/info     # 电视是什么
curl http://192.168.1.42:8765/api/ha/state    # 电视现在在做什么

curl -X POST http://192.168.1.42:8765/api/ha/notify \
  -d '{"title":"门铃","message":"有人在门口","image":"http://…/doorbell.jpg","tts":true,"duration":20}'

curl -X POST http://192.168.1.42:8765/api/ha/command -d '{"action":"screen_off"}'
```

`/api/ha/state` 返回 `screen`（`on`/`off`）、`screen_mode`（`awake`/`dim`/`sleep`）、`message`、`media`
（`title`、`artist`、`artwork`、`app`、`app_label`、`playing`、`paused`、`position`、`duration`）、
`volume`、`volume_level`、`volume_max`、`muted`、`overlay`。

接口没有鉴权：同一个 Wi-Fi 下的任何设备都能访问，和手机遥控器的情况一样。在意的话就在路由器上封掉 8765。

---

## 六、常见问题

| 现象 | 原因与处理 |
|---|---|
| 添加集成时报「无法连接」 | 地址要填电视的；并且 Halo TV 得在跑。在同一 Wi-Fi 的浏览器里打开 `http://<电视IP>:8765` 验证。 |
| 消息只在 Halo TV 前台时出现 | 缺悬浮窗授权。执行上面的 `appops set … SYSTEM_ALERT_WINDOW allow`。 |
| Netflix 里暂停没反应 | 缺通知访问授权，读不到媒体会话，退化发送媒体键，个别应用不响应。补上授权再试。 |
| 不显示标题和封面 | 同上，这些信息来自媒体会话。氛围屏自己的歌曲没有这个授权也能读到。 |
| 图片一直不出来 | 电视下载不到这个地址。请**在电视上的浏览器**打开图片地址试一次。 |
| 电视没真正休眠，只是黑了 | 大多数电视如此：`goToSleep` 需要系统签名。黑窗口撤掉了「保持常亮」，电视自身的屏保/睡眠定时器会接着生效。 |
| 屏幕黑了回不来 | 按遥控器任意键即可。黑窗口设计上就是「任意键退出」，退出后状态立刻回到 `screen: on`。 |
| 消息太长/图太大 | 请求体上限 12 MB，图片上限 8 MB，超过会被拒绝或忽略。 |
