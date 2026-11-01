# 羽光相机 / ShotLab

一款面向**手机摄影教学**的 Android 相机应用，把专业相机的核心参数（快门速度、感光度 ISO、白平衡、曝光补偿、对焦/测光分离、曝光锁定）做成直观可调的界面，并内置**离线语音触发拍照**、**构图辅助叠层**、**素材库（照片+视频）**等教学与创作辅助功能。

> 应用包名：`com.teacher.shotlab` ｜ 最低支持：Android 10（API 29）｜ 目标：Android 14（API 34）

---

## 功能特性

- **全手动曝光控制**：快门、ISO、白平衡、曝光补偿独立调节；自动档显示 `S 自动` / `ISO 自动` / `WB 自动` 前缀，便于教学对照。
- **对焦 / 测光分离**：点按屏幕设定对焦点，长按或拖动分离测光点；支持**曝光锁定**（锁定快门/ISO/白平衡，不锁定对焦），屏幕显示对焦框 + 小锁图标一键切换。
- **离线语音触发**：内置 TFLite 关键词唤醒模型（`assets/kws.tflite`，约 5 类指令），16kHz 音频特征提取，全程**离线、不依赖系统语音服务**，无联网隐私风险。
- **多镜头切换与变焦**：主摄 / 超广角 / 长焦物理镜头枚举与平滑变焦；支持画幅、帧率、分辨率切换。
- **构图辅助叠层**：三分法、中心圈、水平仪等可叠加在预览上的参考线。
- **应用内素材库**：本地照片与视频统一浏览（系统相册在部分机型无法同时显示视频，故内置）。
- **正圆启动图标**：ColorOS 等系统会把启动封面强制裁成圆角方形，本应用采用「纯黑图标隐身 + App 首帧自绘正圆 logo」方案，保证启动画面始终为正圆。

---

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 | Kotlin |
| UI | View + ViewBinding，自绘叠层（CompositionOverlay） |
| 相机 | Camera2（`Camera2Engine` / `Camera2Reflect` 反射适配不同厂商 HAL） |
| 语音 | TensorFlow Lite 2.16（`VoiceTrigger` 关键词唤醒） |
| 构建 | Gradle 8.4（自带 wrapper，无需预装 Gradle） |
| 最低/目标 SDK | minSdk 29 / targetSdk 34 / compileSdk 35 |

---

## 快速开始

### 环境要求

- **JDK 17**（构建脚本默认使用 Java 17）
- **Android SDK**（含对应平台的 build-tools 与 platform）
- 一台 Android 10+ 的真机（Camera2 全部参数能力依赖硬件，模拟器无法完整验证）

### 本地配置（不入库）

仓库根目录需有一个 `local.properties` 指明 SDK 路径（已加入 `.gitignore`，不会上传）：

```properties
sdk.dir=/你的/Android/Sdk
```

> 该文件通常由 Android Studio 自动生成；手动创建一份即可。

### 构建

```bash
# 1) 赋可执行权限（首次）
chmod +x gradlew build_debug.sh build_release.sh

# 2) 构建 debug 包（不混淆、不签名）
./build_debug.sh
# 产物：dist/ShotLab-debug.apk

# 3) 构建签名 release 包（可选）
#    先复制 keystore.properties.example 为 keystore.properties 并填好密钥信息，
#    把签名密钥 shotlab-release.keystore 放到仓库根目录（二者均被 .gitignore 忽略）
cp keystore.properties.example keystore.properties   # 然后编辑填写真实密码
./build_release.sh
# 产物：dist/ShotLab-release.apk
```

也可以直接用 Gradle 任务：

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease   # 签名包（需已配置 keystore.properties）
```

---

## 目录结构

```
ShotLab/
├── app/
│   ├── build.gradle.kts          # 模块构建（含可配置的签名块）
│   ├── proguard-rules.pro        # R8 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/               # 离线语音模型 kws.tflite / kws_mel.txt / kws_spec.json
│       ├── java/com/teacher/shotlab/
│       │   ├── MainActivity.kt        # 主界面与手势交互
│       │   ├── Camera2Engine.kt       # Camera2 引擎（预览/录像/参数）
│       │   ├── Camera2Reflect.kt      # 厂商 HAL 反射适配
│       │   ├── CompositionOverlay.kt  # 构图辅助叠层
│       │   ├── VoiceTrigger.kt        # TFLite 关键词唤醒
│       │   ├── VoiceEngine.kt         # 录音与特征管线
│       │   ├── GalleryActivity.kt     # 素材库（照片+视频）
│       │   ├── Lenses.kt / ManualCamera.kt / Palette.kt
│       │   └── ...
│       └── res/                   # 布局、图标（含正圆启动图标）、主题
├── tools/
│   ├── train_kws.py              # 训练离线语音唤醒模型的脚本
│   └── make_icon.py              # 生成各密度启动图标
├── gradle/wrapper/               # Gradle wrapper（已入库，clone 后可直接构建）
├── keystore.properties.example   # 签名配置模板（复制为 keystore.properties 使用）
├── build_debug.sh / build_release.sh
├── settings.gradle.kts
└── README.md
```

---

## 权限说明

应用运行需要以下权限，均用于相机核心功能，无后台收集行为：

- `CAMERA`：取景与拍摄
- `RECORD_AUDIO`：离线语音触发拍照
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`（Android 13+）：素材库浏览本地照片与视频
- `WRITE_EXTERNAL_STORAGE`（旧版本兼容）：保存拍摄成果

语音识别完全在设备端完成，录音数据不上传任何服务器。

---

## 许可证

本项目以 **Apache License 2.0** 发布，详见 [LICENSE](LICENSE)。
