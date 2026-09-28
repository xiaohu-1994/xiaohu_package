# xiaohu-package

一个面向 Minecraft 26.2 的 Paper 服务端插件：把服务器上的资源包统一分发给玩家。

## 功能

- **内置下载服务** —— 插件自己起 HTTP/HTTPS 服务直接给玩家下发资源包，不依赖网盘或第三方 CDN。
- **全局带宽上限** —— 所有玩家的下载共享一个带宽配额，按活跃会话公平分配，避免个别玩家占满出口带宽。
- **多包合并** —— 把 `packs/` 下的多个 zip 合并成一个统一下发；同名文件按文件名排序，靠后的覆盖靠前的。
- **自动拆分** —— 合并结果超过单包体积上限时自动拆成多份，绕开客户端 250 MB 的硬限制。
- **强制收包 / 询问模式** —— 进服直接下发，或发聊天询问（可点击 [是] / [否]），未选择前按间隔重发。
- **基岩版识别** —— 区分 Java 版与基岩版玩家分别处理。
- **命令管理** —— 重发、重载、查看下发状态（见下方「插件使用指令」）。

## 插件使用指令

### 管理指令 `/xiaohu_package`

需要权限 `xiaohu_package.admin`（默认仅 OP 拥有）。无参数、或输入未知子命令时，都显示帮助；帮助里的每一条命令都可点击，会自动填入聊天框。

| 指令 | 作用 |
| --- | --- |
| `/xiaohu_package help` | 显示帮助（可点击） |
| `/xiaohu_package resend <玩家>` | 给指定玩家重发资源包（该玩家须在线，否则提示不在线） |
| `/xiaohu_package resendall` | 给全体在线玩家重发资源包 |
| `/xiaohu_package reload` | 重载配置；源包没有变化就直接复用，不重新合成 |
| `/xiaohu_package reload force` | 强制重新合成资源包（等价写法 `reload -f`） |
| `/xiaohu_package status` | 查看当前状态 |

**`reload` 的行为**：在后台执行，不占用服务端主线程。重载期间下载服务暂停，完成后自动恢复；期间进服的玩家会被登记下来，生成完成后自动补发。发送者如果是玩家，进度会实时发到该玩家。已有重载在跑时会提示稍后再试。

**`status` 会输出**：带宽上限、下载服务的协议与地址、监听状态、端口占用检测、强制收包开关、合成模式与资源包数量，以及每个资源包的文件名和下载 URL。

### 玩家指令 `/xiaohu_packanswer <yes|no>`

回答"是否下载资源包"的聊天询问，**仅限玩家使用**（控制台执行会提示"仅限玩家使用"）。

正常游玩时不需要手动敲这条命令 —— 询问消息里的 `[是]` / `[否]` 本身就是按钮，点一下等同于执行它。该指令只在 `packs.force: false`（询问模式）下才会用到。

## 构建

```sh
gradlew.bat build        # Windows
./gradlew build          # Linux / macOS
```

首次构建会拉取 Gradle、JDK 25 toolchain 和依赖，大约需要 5-15 分钟。

产物为 `build/libs/xiaohu_package-1.9.0.jar`（普通 jar，`paper-api` 是 `compileOnly`，不会打进 jar）。

## 安装

把 jar 放进服务端 `plugins/` 目录后重启服务端。首次启动会生成 `plugins/xiaohu_package/config.yml`，至少要改这几项：

| 配置项 | 说明 |
| --- | --- |
| `server.ip` | 玩家能访问到的地址（公网 IP 或域名） |
| `server.port` | 下载服务监听端口 |
| `server.https` | 是否启用 HTTPS；开启后首次运行自动生成自签证书，也可指定已有的 PKCS12 keystore |
| `bandwidth.mbps` | 全局带宽上限 |
| `packs.folder` | 存放资源包 zip 的目录 |

## 主要类

| 类 | 职责 |
| --- | --- |
| `XiaohuPackagePlugin` | 插件主类，装配各部分 |
| `PackServer` | 内置 HTTP/HTTPS 下载服务 |
| `BandwidthAllocator` | 全局带宽的公平分配 |
| `ResourcePackMerger` / `ResourcePackSplitter` | 资源包合并与超限拆分 |
| `ResourcePackManager` | 资源包扫描、生成与下发 |
| `PlayerPackListener` / `PlayerTracker` | 玩家侧资源包状态跟踪 |
| `PackCommandExecutor` / `PackTabCompleter` | 命令与补全 |

## 作者

Xiaohu · 许可：[Apache License 2.0](LICENSE)
