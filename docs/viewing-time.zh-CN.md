# 看电视时间控制

给 Halo TV 加了一个按天累计的观看计时器：定期提醒休息、到量熄屏。所有阈值都能通过电视上已有的
HTTP 服务（端口 8765）随时修改，不需要重新编译或重启。

## 默认行为

| 触发点 | 默认 | 动作 |
|---|---|---|
| 每累计观看 30 分钟 | `break_interval_min = 30` | 暂停当前视频 → 全屏弹窗 → TTS 朗读「看电视已30分钟，请看看远处，休息5分钟。」→ 暂停计时 5 分钟 |
| 累计 2 小时 20 分 | `daily_limit_min - warn_before_min`，即 150 − 10 | 弹窗 + TTS：「今天看电视已满2小时20分，10分钟后自动关机。」只提醒一次 |
| 累计 2 小时 30 分 | `daily_limit_min = 150` | 弹窗 + TTS「今天看电视已满2小时30分，电视即将关闭。」→ 12 秒后熄屏，当天不再重复 |

> **关于「提前 10 分钟」**：按 2 小时 30 分关机、提前 10 分钟算，提醒点是 **2 小时 20 分**。
> 需求原文写的是「已满2小时10分」，这里按「提前 10 分钟」的字面逻辑实现。如果确实要在 2 小时 10 分
> 提醒，把 `warn_before_min` 改成 `20` 即可，文案会自动跟着变成「已满2小时10分，20分钟后自动关机」。

计时是**按天**的，本地日期一变自动清零；重启电视后当天的累计值不会丢。

## 什么算「看电视」

需求是「只在主页的时间不算，按打开 app 时间统计」。实现为：只有同时满足下列条件才累计。

1. 功能已启用（`enabled`）；
2. 屏幕亮着、未锁屏，且不是我们主动熄的屏；
3. **Halo 自己的任何界面都不在前台**——主页、氛围屏、弹窗消息、黑屏窗口都不算；
4. 不在护眼休息期，也不在提醒弹窗停留期间；
5. 当日总量尚未到上限（到点后不再累计，避免数字继续涨）。

判定「是否在主页」有两条信号，都不需要新增权限：

- **主信号**：`MainActivity` 的 `onResume` / `onPause`。Halo 作为默认桌面时，用户一离开主页打开
  Netflix、飞牛 TV 等应用，主页就 `onPause`，计时开始；按 Home 回来即停止。
- **辅助信号**：`HomeKeyService`（无障碍服务，用 `--keep-stock` 安装或 Fire TV 时启用）上报当前窗口
  所属包名。系统桌面、设置、SystemUI 同样不计入。窗口事件 30 秒后失效，避免陈旧事件让计时卡住。

因此**不需要** `PACKAGE_USAGE_STATS`，也不需要常驻前台服务。计时精度 5 秒；进程被系统回收期间的
时间不补记（宁可少算，不会误罚）。

## HTTP 接口

电视 IP 以 `192.168.1.42` 为例。也可以先用 ADB 转发，在电脑上直接测：

```bash
adb forward tcp:28765 tcp:8765
curl http://127.0.0.1:28765/api/tvtime
```

### 查询状态

```bash
curl http://192.168.1.42:8765/api/tvtime
# 或 /api/tvtime/status，两者等价
```

返回 `config`（当前配置）与 `today`（当天状态）。`today.reason` 说明此刻为什么在/不在计时：

| `reason` | 含义 |
|---|---|
| `watching` | 正在累计 |
| `home_screen` | 在主页 / Halo 自己的界面 |
| `screen_off` / `locked` | 屏幕熄灭或锁屏 |
| `rest_break` | 护眼休息期 |
| `showing_notice` | 提醒弹窗还在 |
| `disabled` / `limit_reached` | 功能关闭 / 今日已到上限 |

### 修改配置

只需提交要改的键，其余保持原值；返回值含修改后的完整状态。未知键会在 `unknown` 里列出。

```bash
# 改为每 45 分钟提醒一次、每天最多 2 小时、提前 15 分钟预警
curl -X POST http://192.168.1.42:8765/api/tvtime/config \
  -H 'Content-Type: application/json' \
  -d '{"break_interval_min":45,"daily_limit_min":120,"warn_before_min":15}'

# 临时关闭 / 重新开启（不清除累计）
curl -X POST http://192.168.1.42:8765/api/tvtime/config -d '{"enabled":false}'
curl -X POST http://192.168.1.42:8765/api/tvtime/config -d '{"enabled":true}'
```

| 键 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `enabled` | bool | `true` | — | 总开关 |
| `break_interval_min` | int | `30` | 1–240 | 每累计多少分钟提醒一次护眼 |
| `break_minutes` | int | `5` | 0–120 | 文案里的休息分钟数，也是暂停计时的时长 |
| `daily_limit_min` | int | `150` | 1–1440 | 每日总量上限（2 小时 30 分） |
| `warn_before_min` | int | `10` | 0–120 | 提前多少分钟预警 |
| `pause_on_break` | bool | `true` | — | 护眼时是否暂停当前视频 |
| `pause_during_break` | bool | `true` | — | 休息期内是否停止累计 |
| `tts` | bool | `true` | — | 提醒是否朗读 |
| `auto_shutdown` | bool | `true` | — | 到量是否真的熄屏（`false` 只提醒） |
| `message_seconds` | int | `45` | 5–600 | 弹窗停留秒数 |
| `shutdown_delay_s` | int | `12` | 0–300 | 到量后延迟熄屏，留出朗读时间 |
| `language` | string | `zh-CN` | — | TTS 语言标签，留空用系统语言 |

改小 `break_interval_min` 不会立刻补发一次提醒（下一次从「当前累计 + 新间隔」算起）。改小
`daily_limit_min` 使当前累计已超上限时，下一个 5 秒心跳就会执行到量动作——这是预期行为。

### 重置与增减

```bash
# 清零今日累计，并清除「已提醒 / 已关机」标记（家长临时放行）
curl -X POST http://192.168.1.42:8765/api/tvtime/reset

# 增加 20 分钟额度；负数则扣回
curl -X POST http://192.168.1.42:8765/api/tvtime/adjust -d '{"minutes":20}'
```

`adjust` 也是快速验证阈值的方法：把间隔调成 1 分钟、总量调成 3 分钟后 `{"minutes":3}`，
下一个心跳就会走完整的提醒/关机流程，测完 `reset` + 改回默认值即可。

## 与 Home Assistant 的关系

`/api/ha/state` 新增 `viewing` 字段，HA 集成轮询时就能看到：

```json
"viewing": {
  "enabled": true,
  "watched_min": 34.2,
  "remaining_min": 115.8,
  "daily_limit_min": 150,
  "break_interval_min": 30,
  "counting": true,
  "limit_reached": false
}
```

`halo_tv.command` 服务新增三个动作：`viewing_reset`（清零今日）、`viewing_off` / `viewing_on`
（临时停用/启用）。更细的阈值修改仍走 `/api/tvtime/config`，可以用 HA 的
`rest_command` 转发：

```yaml
rest_command:
  halo_tv_time:
    url: "http://192.168.1.42:8765/api/tvtime/config"
    method: POST
    content_type: "application/json"
    payload: '{"daily_limit_min": {{ states("input_number.tv_minutes") | int }}}'
```

## 关于「自动关闭电视机」的能力边界

普通第三方应用**无法真正断电关机**：`PowerManager.goToSleep()` 是签名级权限，反射调用在正常安装的
TV 上会失败。到量时执行的是已有的 `ScreenPower.off()`：先尝试系统休眠，失败则盖一层全黑、亮度 0
且不持有 `KEEP_SCREEN_ON` 的窗口，由电视自身的睡眠定时器接手。**任意按键可退出黑屏**——这是刻意
设计，避免网络故障把电视锁死。

如果确实需要物理断电，有三条外部路径，都不在本功能里：

1. 用 Home Assistant 控制智能插座 / 红外遥控器（推荐，配合 `viewing.limit_reached` 状态）；
2. 把 Halo TV 设为设备所有者（`dpm set-device-owner`）后调用 `lockNow()`；
3. 已 root 的设备执行 `reboot -p`。

## 已知限制

- 计时基于「不在主页 + 屏幕亮 + 未锁屏」，无法区分「在看视频」和「在某个应用里看静态文字」。
  按需求的口径（打开 app 的时间）这是准确的。
- 进程被系统杀掉期间不补记时间；在长期不休眠的电视上基本不会发生。
- 提醒弹窗依靠后台启动 Activity，需要安装脚本授予的悬浮窗权限
  （`appops set com.kartikmodi.halo.home SYSTEM_ALERT_WINDOW allow`）；未授权时 HTTP 仍返回
  `ok: true`，但电视上不会弹窗。
- 中文 TTS 取决于电视自带的语音引擎。首次使用可能需要一次性接受系统语音引擎的隐私提示；
  没有中文引擎时会回退到系统语言。
