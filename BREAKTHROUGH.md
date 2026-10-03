# 路线 B 关键突破：输入法侧成功读取微信输入框

## 结论（2026-10-03 实测）

**微信对无障碍屏蔽，但对输入法完全透明。** 输入法路线可行。

## 决定性日志证据

```
QQCatIME CALL onStartInputView hostPkg=com.tencent.mm fieldId=2131300186
QQCatIME SNAPSHOT host=com.tencent.mm before=[ni hao] sel=[]
QQCatIME SNAPSHOT host=com.tencent.mm before=[你好] sel=[]
QQCatIME SNAPSHOT host=com.tencent.mm before=[你好。] sel=[]
```

- 宿主包名可读：`host=com.tencent.mm`
- 拼音阶段可读：`before=[ni hao]`
- 上屏中文可读：`before=[你好]`
- 标点可读：`before=[你好。]`

## 关键构建要点（踩坑记录）

1. **必须包含 `assets/xposed_init`**，内容为入口类全名（如 `org.dsh.qqcatime.Probe`）。
   缺失此文件 LSPosed 不会识别模块（列表里看不到）。
2. AndroidManifest 需 4 个 meta-data：
   - `xposedmodule` = true
   - `xposeddescription`
   - `xposedminversion` = 82
   - `xposedscope` = @array/xposed_scope（列出 com.tencent.wetype）
3. 模块 APK 由用户手动在 LSPosed 管理器启用 + 勾作用域后才能生效。
4. 用 `InputMethodService.getCurrentInputEditorInfo().packageName` 取宿主包名。
5. 用 `InputMethodService.getCurrentInputConnection().getTextBeforeCursor()` 取文本。
6. 钩 `onStartInputView` / `onStartInput` 捕获服务实例（用 param.thisObject）。

## 复现步骤

```sh
# 构建
cd /root/dsh/qqcat-ime && python3.12 build.py
# 安装
adb install -r dist/QQCatIME-*.apk
# 用户手动：LSPosed 管理器 → 启用「微信输入法猫娘模块」→ 作用域勾选「微信输入法」
# 重启输入法进程后生效
```

## 下一步

- [ ] 改造为双向：读 + 写（ACTION_SET_TEXT 等价物：InputConnection.commitText/setComposingText）
- [ ] 与 sh 脚本通信（socket / 文件）
- [ ] 白名单 txt（默认 com.tencent.mobileqq，可加 com.tencent.mm）
- [ ] 触发策略（检测到句末标点自动改写）
