# 语音输入自动发送的拦截方案

## 问题

微信输入法 3.5.4 起，在微信/QQ 里语音转文字后会**自动发送**。
原有的 500ms 轮询方案来不及改写就被发出去了。

## 实测时序（决定性证据）

```
+99620ms  >> setComposingText [最终全文]     ← 语音识别，流式增量更新
+99634ms  >> finishComposingText            ← 定稿
+99639ms  >> performEditorAction [4]        ← IME_ACTION_SEND，发送！
```

**定稿到发送只隔 5 毫秒**，轮询（500ms）完全来不及。

### 关键事实

1. 语音文本**全程走 `setComposingText`**（流式，每 100~500ms 一次），
   **不用 `commitText`**。
2. 发送动作是 `performEditorAction(EditorInfo.IME_ACTION_SEND == 4)`。
3. InputConnection 的真实实现类是
   **`android.inputmethodservice.RemoteInputConnection`**
   （不是 `com.android.internal.inputmethod.RemoteInputConnectionImpl`，
   后者在本机不存在）。

## 解决方案

### 1. 在「发送动作」里抢先改写

钩 `RemoteInputConnection` 的：

| 方法 | 作用 |
|---|---|
| `setComposingText` | 标记「正在识别」，让轮询让路 |
| `finishComposingText` | **主拦截点** —— 定稿后立即改写 |
| `performEditorAction(4)` | 兜底拦截点 |
| `sendKeyEvent(ENTER)` | 兜底拦截点 |

### 2. 轮询必须让路

语音识别期间，轮询若插手改写，输入法随后的 `setComposingText`
会把整段覆盖回去，导致**文本重复**（实测出现过前缀重复 3 次）。

```java
if (composingQuiet()) {   // 正在识别 或 定稿后 1.5s 内
    continue;             // 完全不干预
}
```

### 3. 改写用 CAS 防重入

`transformBeforeSend()` 用 `AtomicBoolean.compareAndSet` 保护，
避免改写动作自身又触发钩子造成递归。

## 验证结果

微信语音说了一长段，自动发送前成功改写：

```
原文：这期教你们用goslock激活...都怪你老爸...
改写：这期教主人们用goslock激活...都怪主人老爸...
      （句号前全部加喵，末尾追加 /ᐠ•ㅇ•ᐟ）
```

截图确认发出的消息内容正确，无重复污染。


## 关键坑：语音识别常常没有句末标点

### 现象

同样的语音发送，**说长了能成功，说短了不生效**。

### 根因

诊断日志暴露得很清楚：

```
TS text=[你好 你好]          ← 语音识别结果，无任何句末标点
TS want=[你好 你好] changed=false
TS abort: no change needed   ← 原规则「无标点不动」直接跳过
```

原 `transform()` 的触发条件是 `needsMiao()` —— **只在出现未喵化的句末标点时才改写**。
这个规则适合「打字」，因为打字时用户会自己敲 `。！？`；
但**语音识别结果经常不带标点**，短句尤其如此（长句才容易出句号），
于是短语音永远不会被喵化。

### 修复：`transformLoose()`

发送前专用，不再要求必须有标点：

| 规则 | 打字 `transform()` | 语音发送 `transformLoose()` |
|---|---|---|
| 我 → 本喵 | 有标点才做 | **无条件** |
| 你 → 主人 | 有标点才做 | **无条件** |
| 句末加「喵」 | 有标点才加 | 有标点才加 |
| 末尾颜文字 | 有标点才加 | **无条件** |

幂等保护：若文本已无可替换的人称、且末尾已是词库里的颜文字，则原样返回。

### 验证

```
TS text=[你好呀]                                  ← 无标点，旧版会跳过
TS want=[主人好呀^•͈༝•^ฅ] changed=true              ← 新版生效
SEND_TRANSFORM host=com.tencent.mobileqq ok=true
```

截图确认 QQ 里实际发出的就是 `主人好(=^･ω･^=)`。

## 版本

- `QQCatIME-3.1.apk`：首次实现发送前拦截
- `QQCatIME-3.4.apk`：加入宽松模式，修复短语音无标点不触发的问题
- `QQCatIME-4.0.apk`：**当前正式版**（诊断日志已清理）
