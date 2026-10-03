# 路线 B：hook 输入法读取/改写输入框（新开发路线）

## 背景
路线 A（无障碍，已交付 `/data/adb/QQ/qqcat.sh`）对微信不可用：
微信 `com.tencent.mm` 主动屏蔽无障碍节点树（根节点 children=0，
无 FLAG_SECURE，isAccessibilityTool 已为 true 仍为空）。
而输入法进程持有 InputConnection，能看到任意 App 的输入框内容。

## 已验证的设备事实（实测）
- LSPosed 框架运行中：lspd pid 1831
- 已有可用 hook 模块：org.dsh.wetypelite（微信输入法精简 v1.1）
  - 构建链路：/root/dsh/wetype-lite/build_apk.py
  - 依赖：deps/xposed-api-82.jar，tools/android-33/android.jar
  - 已验证可在 com.tencent.wetype 主进程与 :hld 进程注入
- 微信输入法 com.tencent.wetype v3.5.3 versionCode 56201
  - 进程：com.tencent.wetype / com.tencent.wetype:hld
- 设备有 nc（toybox）、ip

## 技术方案（待实现）
1. LSPosed 模块 hook 输入法进程
2. 在输入法拿到 InputMethodService 后，反射/包装 getCurrentInputConnection()
3. 通过 socket 或文件与 sh 脚本通信
   - sh 侧负责：白名单判断、文本转换（喵化）、触发策略
   - 模块侧负责：读当前输入框文本、写回
4. 白名单 txt：默认 com.tencent.mobileqq，可追加 com.tencent.mm 等

## 未验证 / 风险
- 输入法是否总能拿到宿主包名（getCurrentInputEditorInfo().packageName 应可用）
- 微信是否对输入法输入内容也做隔离（实测待做）
- 与路线 A 的无障碍注册是否会冲突（不同机制，理论不冲突）

## 状态
- [x] 可行性调研
- [ ] 模块改造：读取 InputConnection 文本并上报
- [ ] sh 侧对接与白名单
- [ ] 微信实测
