# 长城行车记录仪视频下载 App

用于替代原车 App，从长城/哈弗车机的行车记录仪下载视频的 Android 应用。用户使用中文沟通。

## 基本信息

- 应用名：长城行车记录仪视频下载（`app/src/main/res/values/strings.xml`）
- 包名 / applicationId / namespace：`net.vsean.gwm_dvr_download`（2026-09-14 从 `com.gwm.dvr_download` 改名，旧包已分发，与新包是两个独立的 App）
- minSdk 29，compileSdk / targetSdk 36（用户手机是 Android 16）；只打包 `arm64-v8a`
- 技术栈：Kotlin、AGP 9.4.0（内置 Kotlin，不要再加 `org.jetbrains.kotlin.android` 插件）、Gradle 9.7.1、Java 25
- UI：Material 3 + View/XML（没有用 Compose），主色 `#1A56DB`（与图标一致），支持深色模式
- 扫码：CameraX + ML Kit barcode-scanning（模型打包在 APK 内，不依赖 GMS）。之前的 zxing 横屏且识别不了高密度码，已弃用
- Git 仓库（分支 `main`），计划推送到 GitHub 私有仓库。`.gitignore` 排除了 `signing/`（密钥和密码绝不入库，需另行备份）、`dist/`、`local.properties`、构建目录和视频文件；`.gitattributes` 保证 `gradlew` 是 LF 换行

## 版本号

在 `app/build.gradle.kts` 顶部：
- `appVersionName = "1.1"`：显示给用户的版本号，手动修改，不要加日期后缀
- `versionCode`：每次构建自动生成，取自 2026-01-01 00:00（北京时间）起经过的分钟数，保证可以覆盖安装

## 构建（本机 Windows，首选）

本机已安装好编译环境：
- JDK：Microsoft OpenJDK 25（`C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot`，系统级 `JAVA_HOME` 已设置）
- Android SDK：`%LOCALAPPDATA%\Android\Sdk`（cmdline-tools/latest、platforms;android-36、build-tools;36.0.0、platform-tools），用户级 `ANDROID_HOME` 已设置，`platform-tools`（adb）已加入用户 PATH
- 项目根目录的 `local.properties` 指向上面的 SDK（本机专用）
- 管理 SDK 包用 Android CLI（取代已弃用的 sdkmanager）。已按官方方式独立安装到 `%USERPROFILE%\AppData\AndroidCLI\android.exe`（已加入用户 PATH，版本 1.0.16261425），实际执行的程序是 `%USERPROFILE%\.android\bin\android-cli.exe`。包名用 `/` 分隔，例如 `android sdk install platforms/android-36 build-tools/36.0.0`
- **已知问题**：在本机上直接从 PowerShell（包括 `Start-Process`）运行 `android sdk install/update/remove` 会崩溃（`ucrtbase.dll` 异常 0xC0000409，没有任何输出）；**通过 `cmd /c` 运行就正常**，与代码页无关。`android info`、`android sdk list` 直接运行也没问题。所以统一写成：`cmd /c "android sdk install platforms/android-36"`
- **不要运行 `android init`**：它会往 `~\.claude\skills` 和 `~\.copilot\skills` 写入 android-cli 技能包，用户明确不要（已删除过一次）
- `cmdline-tools\latest\bin\` 里也有一个 `android.exe`，那个启动器版本较旧，不要用它；`sdkmanager.bat` 仍可作为备用（在 Windows 上要用 `--package_file=<文件>` 传包名，否则 `;` 会被拆开）

**一键构建脚本（首选）**：`scripts\build.ps1`（或双击 `scripts\build.cmd`），可加 `-SkipTests`、`-Clean`。脚本会自动找 JDK/SDK、跑单元测试、编译 release、**校验签名证书 SHA-256**（不一致会报错退出）、把 APK 和 mapping 归档到 `dist\`，最后打印包名、版本、APK 路径。在 Claude 的工具里运行：`pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1`

脚本内部等价于以下手动步骤（新开的终端已经有环境变量；在 Claude 的工具里要先显式设置）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot"; $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleRelease --console=plain
$apk = "app\build\outputs\apk\release\app-release.apk"
$vc = [regex]::Match((& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk | Out-String), " versionCode='(\d+)'").Groups[1].Value
New-Item -ItemType Directory -Force dist\mapping | Out-Null
Copy-Item $apk dist\GWM-DVR-Download.apk -Force
Copy-Item app\build\outputs\mapping\release\mapping.txt "dist\mapping\mapping-$vc.txt" -Force
```

第一次完整编译约 2 分钟。编译有一条已知的无害警告：`ScanActivity.kt` 的 `@OptIn(ExperimentalGetImage::class)` has no effect。
手机开启 USB 调试后，可以用 `adb install -r dist\GWM-DVR-Download.apk` 安装，用 `adb logcat -b crash` 查看崩溃日志。

## 构建（备用：远程 Ubuntu 服务器）

也可以在局域网服务器上编译：
- SSH：`root@192.168.188.66`，已配置密钥登录（`BatchMode=yes` 可用）
- SDK：`/opt/android-sdk`（platforms;android-36、build-tools;36.0.0）；Gradle：`/opt/gradle/gradle-9.7.1`
- 服务器上的工程目录：`/root/car_video`，其中 `local.properties` 和 `gradle.properties` 是服务器专用的，同步时不要覆盖
- 服务器上的 Java 必须加 `-Djava.net.preferIPv4Stack=true`，否则 Gradle 下载会失败
- 服务器没有 KVM，跑不了模拟器，UI 效果只能让用户在手机上截图反馈

一条命令完成同步、单元测试、编译、取回 APK（在项目根目录用 Bash 执行）：

```bash
tar czf - --exclude=.gradle --exclude=build --exclude=.idea --exclude=gradle.properties --exclude=local.properties \
  --exclude='*.apk' --exclude='*.mp4' --exclude=./dist --exclude=./docs --exclude=.kotlin . \
| ssh -o BatchMode=yes root@192.168.188.66 'cd /root/car_video && rm -rf app/src signing && tar xzf - \
  && export ANDROID_HOME=/opt/android-sdk JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true \
  && ./gradlew testDebugUnitTest assembleRelease --console=plain -q 2>&1 | grep -v "Picked up" | tail -30; \
  /opt/android-sdk/build-tools/36.0.0/aapt dump badging app/build/outputs/apk/release/app-release.apk | grep "^package"' \
&& R=root@192.168.188.66:/root/car_video/app/build/outputs \
&& VC=$(ssh -o BatchMode=yes root@192.168.188.66 "/opt/android-sdk/build-tools/36.0.0/aapt dump badging /root/car_video/app/build/outputs/apk/release/app-release.apk" | grep -oE "versionCode='[0-9]+'" | grep -oE '[0-9]+') \
&& mkdir -p dist/mapping \
&& scp -q -o BatchMode=yes $R/apk/release/app-release.apk ./dist/GWM-DVR-Download.apk \
&& scp -q -o BatchMode=yes $R/mapping/release/mapping.txt "dist/mapping/mapping-$VC.txt"
```

两种构建方式的产物位置相同：release 版 APK 在 `dist/GWM-DVR-Download.apk`，R8 映射保存在 `dist/mapping/mapping-<versionCode>.txt`。

## 目录结构

- 根目录只放 Gradle 工程文件、`README.md`、`CLAUDE.md`、`.gitignore`、`.gitattributes`，**不要把其他文件放在根目录**
- `app/`：Android 应用源码；`tools/`：电脑端 Python 脚本；`scripts/`：构建脚本
- `dist/`：构建产物（APK、`mapping/`）
- `docs/`：参考资料。`packet.txt` 是原车 App 的抓包，`wechat.png` 是微信名片原图（App 里用的是 `app/src/main/res/drawable-nodpi/wechat_qr.png`）
- `signing/`：签名密钥（见下文）

## 签名（非常重要）

- 所有构建（debug 和 release）都用 `signing/release.keystore` 签名，密码和别名在 `signing/keystore.properties`，由 `app/build.gradle.kts` 读取
- 证书：`CN=vibbow`，RSA 4096，别名 `release`，有效期到 2076-09-01，密码是随机生成的
- 证书 SHA-256：`57:E6:C2:B5:08:85:09:F6:F7:E8:A5:7A:4B:11:C9:79:07:F4:5A:35:4C:E8:4D:05:D9:BE:23:EC:10:27:EC:41`，可用 `apksigner verify --print-certs` 核对
- **发布后必须一直使用这个 keystore**，换密钥会导致已安装的用户无法覆盖升级。不要重新生成，不要删除 `signing/`；公开代码时不要带上它，也不要在对话里复述密码
- `signing/legacy-com.gwm/`：旧包名 `com.gwm.dvr_download` 用过的 debug keystore（`CN=Android Debug`），仅作存档。旧包已经分发出去，与新包名是两个独立的 App

## 代码压缩（R8）

- release 构建开启了 `isMinifyEnabled` 和 `isShrinkResources`，但**只压缩、不混淆**（`app/proguard-rules.pro` 里有 `-dontobfuscate`）。APK 从 21 MB 降到约 9.6 MB，其中 ML Kit 的原生库 `libbarhopper_v3.so` 占 4.9 MB
- 原因：开启混淆后，点击扫码就会崩溃（没有拿到崩溃日志，推测是 ML Kit 内部靠反射或 JNI 访问的类被改名或删除）。所以关闭了混淆，并用 `-keep` 完整保留了 `com.google.mlkit`、barhopper、`gms.internal.mlkit_vision_*`。**不要重新开启混淆，也不要删掉这些 keep 规则**
- org.json 是系统类，不会被打包；项目代码没有用反射。如果新增了反射、JSON 映射到数据类或 JNI 调用，要加 keep 规则
- 因为没有混淆，崩溃堆栈可以直接阅读；`dist/mapping/` 仍然会保存，但一般用不上。`dist/mapping/mapping-369703.txt` 是那个混淆后会崩溃的测试包的映射，没有发布过
- 改完代码后，要让用户在真机上测一遍：扫码、连接热点、下载、深色模式、版本号连点 5 次

## 代码结构（`app/src/main/java/net/vsean/gwm_dvr_download/`）

- `MainActivity.kt`：主界面，包括状态卡片、4 个步骤（扫码 → 连接热点 → 车机确认 → 下载）、可折叠日志、底部大按钮；底部版本号连续点击 5 次会弹出作者微信名片（`res/drawable-nodpi/wechat_qr.png`，弹窗不带标题）
- `ScanActivity.kt`：竖屏扫码页，只有「手电筒」「取消」两个按钮，只接受能被 `QrParser` 解析的二维码
- `HotspotConfig.kt`：`QrParser`，负责解析二维码；单元测试在 `app/src/test/.../QrParserTest.kt`
- `WifiConnector.kt`：用 `WifiNetworkSpecifier` + `requestNetwork` 连接热点（系统会弹窗确认，App 关不掉），socket 通过 `network.socketFactory` 绑定到该网络
- `DashcamClient.kt`：车机 TCP 协议实现
- `MediaStoreSink.kt`：保存到相册，视频存 `Movies/CarVideo`，图片存 `Pictures/CarVideo`，其他存 `Download/CarVideo`

`tools/gwm_dl.py`（电脑端下载脚本）和 `tools/mock_car.py`（模拟车机）与 App 使用同一套协议，改协议时要一起改。

## 二维码与协议要点

- 二维码是 `https://app-down.gwm.com.cn/...#/?haval_hotspot=<base64>`，base64 解码后是 `haval://app%2Fserver%2Fhotspot?hotspotName=&hotspotPassword=&port=&socketAddress=`
- 参数值经过**两次 URL 编码**，需要按表单规则解码两次（`+` 当空格）。带空格的中文热点名就是因为这个问题修复过
- 协议（完整表格见 README.md）：1001 → 车机回 1008（等待确认）→ 车机回 1001/200（文件列表）；每个文件依次 1002 → 1003 → 读取 fileSize 字节 → 2001
- 原车 App 的真实抓包：`docs/packet.txt`（Wireshark "Follow TCP Stream" 的十六进制导出，不缩进的行是手机发出的，缩进的行是车机发出的）
- 视频是 H.264 1920×912 25fps，文件里没有 GPS 等隐藏数据；车速、挡位、车架号、时间是直接叠加在画面底部的

## 用户确认过的产品决策

- 每次连接的热点名、密码、IP、端口都不同：不缓存、不支持手动输入、不在界面上显示 IP/端口/密码；只在连接时显示热点名
- 扫码成功后直接自动连接并下载，不弹 Toast 提示
- 扫码页不提供「从相册选择」
- 进度条不要末端的圆点（`trackStopIndicatorSize=0dp`）
