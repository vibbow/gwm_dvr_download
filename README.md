# 行车记录仪视频下载（GWM / 哈弗）

## Android App

在项目根目录执行 `.\gradlew.bat assembleRelease` 编译（需要 JDK 17+ 和 Android SDK 36），APK 输出在 `app/build/outputs/apk/release/`，归档的发布包放在 `dist/`。需要 Android 10 及以上。

目录：`app/` 应用源码，`tools/` 电脑端脚本，`docs/` 抓包等参考资料，`dist/` 构建产物，`signing/` 签名密钥（勿外传）。

使用步骤：
1. 在车机上选好要导出的文件，车机会显示连接二维码。
2. 点「扫码连接并下载」扫码。每次连接的热点信息都不同，App 不保存也不显示这些信息。
3. 扫码成功后，系统会弹窗询问是否连接车机热点，点连接。
4. 车机上会出现确认提示，点确认，随后自动开始下载。
5. 下载完成后，视频在相册的 `Movies/CarVideo`，图片在 `Pictures/CarVideo`。

## 协议

TCP 连接 `socketAddress:port`，每条 JSON 消息以换行结尾：

| 方向 | 内容 |
|---|---|
| 手机 → 车机 | `{"requestCode":1001,"phoneName":"iPhone"}\r\n` |
| 车机 → 手机 | `{"fileList":[...],"requestCode":1008,"statusCode":0}\n`（等待车机上确认） |
| 车机 → 手机 | `{"fileList":[...],"requestCode":1001,"statusCode":200}\n`（已确认） |
| 手机 → 车机 | `{"filePath":"...","requestCode":1002}\r\n`（每个文件一次） |
| 车机 → 手机 | `{"file":{...,"fileSize":N},"requestCode":1002,"statusCode":200}\n` |
| 手机 → 车机 | `{"filePath":"...","requestCode":1003}\r\n`（开始传输） |
| 车机 → 手机 | N 字节原始文件数据 |
| 手机 → 车机 | `{"filePath":"...","requestCode":2001}\r\n`（接收完成） |

二维码中的 `haval_hotspot` 是 base64 编码的 `haval://...?hotspotName=&hotspotPassword=&port=&socketAddress=`，参数值经过了两次 URL 编码。

## tools/

- `gwm_dl.py`：电脑端下载脚本，连上热点后运行 `python gwm_dl.py --qr "<二维码内容>"`。
- `mock_car.py`：模拟车机，没有车时用于调试。
