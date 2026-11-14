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

1. 电视端需要 **Halo TV 3.2 或更新**——集成说的就是电视自己那个 8765 端口。装好：

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

## 三、实体一览

集成按「一个设备」的方式添加电视，下面所有实体都挂在这台电视的设备页下（实体名后缀就是设备名，
下表以 `halo_tv` 为例）。

| 域 | 实体 | 作用 |
|---|---|---|
| `media_player` | `halo_tv` | 电视本体（device_class 是 tv）：开/关屏、播放/暂停/停止、上一个/下一个、音量、静音、音源 |
| `remote` | `halo_tv_remote` | 遥控器：`send_command` 一次按一个键，应用列表也是它的 activity 列表 |
| `button` | 19 个 | 方向×4、确定、返回、主页、菜单、信息、最近、播放暂停、音量±、熄屏、亮屏、**待机**、关消息、停止朗读、重置今日观看 |
| `switch` | 7 个 | 屏幕、静音、观看时间限制、到点熄屏、朗读提醒、休息时暂停播放、休息期间禁止播放 |
| `number` | 7 个 | 每日上限、休息间隔、休息时长、提前提醒、消息停留秒数、熄屏延迟、音量 |
| `sensor` | 14 个 | 今日已看、今日剩余、已看文字版、距下次休息、休息剩余、今日休息次数、计时原因、屏幕、音量、当前应用、正在播放、屏上消息、最后应答时间、集成版本 |
| `binary_sensor` | 6 个 | 屏幕亮着、正在播放、有消息、正在计时、已达每日上限、休息中 |
| `text` | 2 个 | 「消息标题」和「消息」（给「消息」填内容就立刻发到电视上） |

加起来 57 个实体。下表只列常用的几个，全部实体在设备页里都能看到：

| 实体 ID | 含义 |
|---|---|
| `media_player.halo_tv` | 电视本体 |
| `remote.halo_tv_remote` | 遥控器 |
| `button.halo_tv_standby` | 真待机（单向） |
| `button.halo_tv_reset_viewing_time` | 今日观看时间清零 |
| `switch.halo_tv_viewing_time_limits` | 观看时间限制总开关 |
| `switch.halo_tv_go_dark_at_the_limit` | 到上限自动熄屏 |
| `number.halo_tv_daily_limit` | 每日上限（分钟） |
| `number.halo_tv_eye_rest_every` | 每看多少分钟提醒休息 |
| `sensor.halo_tv_remaining_today` | 今天还剩多少分钟 |
| `sensor.halo_tv_watched_text` | 今天看了多久（「1小时20分」） |
| `sensor.halo_tv_next_break_in` | 还有几分钟提醒休息 |
| `binary_sensor.halo_tv_daily_limit_reached` | 今日已达上限 |
| `text.halo_tv_message` | 给电视发消息 |

`media_player` 的开关是**熄屏**，不是待机 —— 熄屏能从 HA 再叫回来，待机不行。要真待机就按
`button.halo_tv_standby`，它会走电视自己的遥控通道进入待机，之后电视会掉线，只能用实体遥控器或
「小爱同学，开机」唤醒。

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

```yaml
# 用遥控器选片：遥控实体一次按一个键
- alias: 电视按确定
  actions:
    - action: remote.send_command
      target:
        entity_id: remote.halo_tv_remote
      data:
        command:
          - down
          - down
          - ok
```

```yaml
# 打开 B 站（音源就是应用名）
action: media_player.select_source
target:
  entity_id: media_player.halo_tv
data:
  source: 云视听小电视
```

### 观看时间：电视里那套，搬到了 HA

这套限制本来就跑在电视里（见 [viewing-time.zh-CN.md](viewing-time.zh-CN.md)），集成只是把它变成实体。
改 `switch` / `number` 就是改电视的设置本身，不需要在 HA 里另写自动化：

```yaml
# 晚上九点后只给 30 分钟，到点自动熄屏
- alias: 晚上收紧观看时间
  triggers:
    - trigger: time
      at: "21:00:00"
  actions:
    - action: number.set_value
      target:
        entity_id: number.halo_tv_daily_limit
      data:
        value: 30
    - action: switch.turn_on
      target:
        entity_id: switch.halo_tv_go_dark_at_the_daily_limit
```

```yaml
# 孩子说作业写完了：今天多给一点
action: halo_tv.set_viewing
data:
  daily_limit_min: 120
```

看还剩多少：`sensor.halo_tv_remaining_today`（分钟）、`sensor.halo_tv_watched_text`（「1小时20分」）、
`sensor.halo_tv_next_break_in`（还有几分钟提醒休息）。到上限时 `binary_sensor.halo_tv_daily_limit_reached` 变 `on`。

---

## 四、指令一览

`halo_tv.command` 的 `action`：

| 指令 | 作用 |
|---|---|
| `pause` / `play` / `play_pause` / `stop` | 播放控制 |
| `next` / `prev` | 当前播放的下一集 / 上一集 |
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

另外三个服务，各管一件事：

| 服务 | 字段 | 作用 |
|---|---|---|
| `halo_tv.key` | `key` | 按一下遥控器键：`up`、`down`、`left`、`right`、`ok`、`back`、`home`、`menu`、`info`、`recents`、`play_pause`、`volume_up`、`volume_down`、`power` |
| `halo_tv.send_message` | `message`、`title`、`image`、`language`、`position`、`duration`、`tts` | 发消息，字段与 notify 一致 |
| `halo_tv.set_viewing` | `enabled`、`daily_limit_min`、`break_interval_min`、`break_minutes`、`warn_before_min`、`pause_on_break`、`pause_during_break`、`tts`、`auto_shutdown`、`message_seconds`、`shutdown_delay_s`、`language`、`warning_text` | 一次改多项观看时间设置 |

```yaml
action: halo_tv.key
data:
  key: power          # 真待机，之后电视会掉线
```

```yaml
action: halo_tv.send_message
data:
  title: 爸爸
  message: 该吃饭了
  tts: true
  duration: 30
```

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
| 不显示标题和封面 | 同上，这些信息来自媒体会话。 |
| 图片一直不出来 | 电视下载不到这个地址。请**在电视上的浏览器**打开图片地址试一次。 |
| 电视没真正休眠，只是黑了 | 大多数电视如此：`goToSleep` 需要系统签名。黑窗口撤掉了「保持常亮」，电视自身的屏保/睡眠定时器会接着生效。 |
| 屏幕黑了回不来 | 按遥控器任意键即可。黑窗口设计上就是「任意键退出」，退出后状态立刻回到 `screen: on`。 |
| 消息太长/图太大 | 请求体上限 12 MB，图片上限 8 MB，超过会被拒绝或忽略。 |
