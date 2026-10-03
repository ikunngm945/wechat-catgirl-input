# 路线 B 状态：双向打通（读 + 写）

## 里程碑（2026-10-03 实测确认）

| 能力 | 状态 | 证据 |
|---|---|---|
| 读取微信输入框 | ✅ | `SNAPSHOT host=com.tencent.mm before=[你好。]` |
| 识别宿主包名 | ✅ | `host=com.tencent.mm` |
| 写回微信输入框 | ✅ | 写入 `[W]` 后读回 `before=[你好。[W]]` |
| 绕过微信无障碍屏蔽 | ✅ | 微信无障碍树为空，但输入法侧完全可见 |

## 结论

**微信对无障碍屏蔽，但对输入法透明。**
路线 B（hook 输入法）是微信场景下唯一可行方案，已双向验证。

## 当前产物

- 源码：`/root/dsh/qqcat-ime/src/org/dsh/qqcatime/Probe.java`
- 构建：`python3.12 /root/dsh/qqcat-ime/build.py`
- APK：`dist/QQCatIME-0.3.apk`（生产版，写回测试已关闭）
- 已安装于设备，LSPosed 已启用，作用域 = com.tencent.wetype

## 构建/部署要点（勿踩坑）

1. **必须有 `assets/xposed_init`**（内容=入口类全名）。
   缺它 LSPosed 管理器列表里根本看不到模块。
2. Manifest 需要 4 个 meta-data（xposedmodule/description/minversion/scope）。
3. 装完必须由用户在 LSPosed 管理器启用 + 勾作用域。
4. 改代码后需重启输入法进程才重新加载：
   `kill -9 <wetype pid>`，再点输入框触发。

## 已确认的 Android API 用法

```java
// 宿主包名
EditorInfo ei = svc.getCurrentInputEditorInfo();
String host = ei.packageName.toString();

// 读文本
InputConnection ic = svc.getCurrentInputConnection();
CharSequence before = ic.getTextBeforeCursor(200, 0);

// 写文本（全量替换）
ic.setComposingText("", 1);
ic.deleteSurroundingText(cur.length(), 0);
ic.commitText(newText, 1);
```

## 下一步（待实现）

- [ ] 改为常驻双向：检测句末标点 → 自动喵化（复用 cat.jar 的转换逻辑）
- [ ] 白名单 txt：默认 com.tencent.mobileqq，可追加 com.tencent.mm 等
- [ ] 与 sh 脚本对接（当前转换逻辑在 cat.jar；可移植进模块或走 socket）
- [ ] 输入法联想干扰处理：写入后清 composing 状态，避免触发候选联想

## 注意

写入后输入法会尝试联想（voice/候选），需要 `setComposingText("",1)` 清状态。
测试时曾误触语音输入，属操作副作用，非模块行为。
