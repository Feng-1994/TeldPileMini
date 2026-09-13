# 特来电家充桩极简控制 App（TeldPileMini）

一个只干一件事的安卓小 App：**看自己家充桩的状态、启动充电、停止充电**。

官方的特来电 App 功能很多、启动慢、广告多；这个 App 打开就是你的那一根桩，一屏搞定，并且**登录一次就够**（令牌到期自动续期，不用反复收短信验证码）。

> 逆向自官方 App v7.16.0 的云端协议（HTTPS 表单 + 自定义签名/加密），全部接口均已实测跑通。
> 仅供个人学习与自用，请遵守特来电的用户协议；「特来电」相关商标归特来电新能源股份有限公司所有。

## 功能

| 功能 | 说明 |
|---|---|
| 手机号 + 验证码登录 | **只需登录一次**：AccessToken 20 分钟过期后自动用 RefreshToken 静默续期 |
| 我的充电桩 | 只列出自己绑定的家充桩（在线状态 + 终端状态） |
| 启动 / 停止充电 | 按官方路由（`CQS-GetStaRoutingByPileCodeV3`）自动选择对应数据中心的启动服务 |
| 实时充电数据 | 功率(kW) · 电压(V) · 电流(A) · 已充电量(度) · 已充时长 · 电池电量(%)，5 秒自动刷新 |
| 订单信息 | 订单号 / 开始时间 / 费用 |
| 登录状态显示 | 界面上直接显示当前令牌到期时间与最近一次自动续期时间 |
| 诊断日志 | 一键复制完整请求日志（排查问题用） |

## 安装

1. 到 [Releases](../../releases) 下载最新 `TeldPileMini_vX.Y.apk`（或自行构建，见下）；
2. 传到手机安装（允许"安装未知来源应用"）；
3. 打开 → 输手机号 → 收验证码 → 登录一次，之后一直免登录。

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 34 / build-tools 34）。

```bash
# 项目根目录建一个 local.properties 指向你的 SDK（不要提交到仓库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleRelease        # 产物：app/build/outputs/apk/release/app-release.apk
```

签名：默认用 debug 签名（`signingConfigs.getByName("debug")`），自用足够；要正式签名请自行配置 `signingConfig`。

## 代码结构

```
app/src/main/java/cn/mini/teldpile/
├── LoginActivity.kt        登录（手机号 + 短信验证码）
├── MainActivity.kt         主页（桩卡片 / 实时数据 / 启停 / 登录状态）
└── api/
    ├── TeldApiClient.kt    网关客户端（登录续期 / 桩列表 / 实时数据 / 启停）
    ├── RequestSigner.kt    签名与加解密（ATS/AVER、DES-CBC、desTeldEncode、AES token）
    └── ApiModels.kt        响应信封 / 桩模型 / 实时数据模型 / 订单模型
```

## 协议要点（自用备忘）

- 网关：`https://sg.teld.cn/api/invoke?SID=<服务码>`，POST `application/x-www-form-urlencoded`
- 请求头签名：`ATS = 秒级时间戳`；`AVER = Base64(DES-CBC-PKCS7(ATS, "UQInaE9V", "siudqUQo"))`
- 业务字段加密：`Base64(DES-CBC-PKCS7(json, "uf1Fia9p", "f72er983"))`
- 启停参数加密：`desTeldEncode`（DES-ECB 随机 8 字符密钥 + Base64 后把密钥按固定位置插回密文）
- 令牌：`X-Token = AccessToken`；会话 `Cookie: TELDSID=<SessionID>`；续期接口 `UserAPI-APP-SRefreshToken`
- **坑点**：表单里的 `TELDAppID` 字段**必须存在且为空串**——不传会报 `TTP-SG-1004 参数【TELDAppID】`，
  填成 H5 用的 GUID 会报 `BOSS-2003 系统异常，请联系客服`（启动充电必失败）。
- 用户数据中心：`CURS-GetUserRouter` 返回的 `IDCSG` 才是该账号的登录主机（如 `sgh1c.teld.cn`），
  桩的启动/停止则走桩自己的 `IDCSG`（如 `sgh2c.teld.cn`）。

## 隐私

- **本 App 不收集、不上传任何数据**，没有统计 SDK、没有广告；
- 手机号只用于向特来电官方接口发验证码，**不落盘**；
- 登录令牌（AccessToken / RefreshToken / SessionID）只存在手机本机的 `SharedPreferences` 里，
  点「退出登录」即清除；
- 只有"诊断日志"会把请求明细写到 App 私有目录（本机可见），方便你自己排查问题。

## 免责声明

本项目为非官方客户端，通过逆向分析官方 App 的公开网络接口实现，仅用于个人学习与自己设备的自用管理。
使用本 App 产生的任何后果（包括但不限于账号风控、充电异常、费用问题）由使用者自行承担。
