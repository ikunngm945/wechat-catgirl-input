package org.dsh.qqcatime;

import android.inputmethodservice.InputMethodService;
import android.os.Process;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信输入法猫娘模块（LSPosed 模块）。
 *
 * 钩在微信输入法进程内，通过 InputConnection 读取/改写宿主输入框，
 * 从而绕开微信对无障碍节点树的屏蔽。
 *
 * 触发：输入框出现新的句末标点 -> 自动喵化
 *   我 -> 本喵    你 -> 主人    句末加「喵」    末尾追加随机颜文字
 *
 * 配置（首次运行自动释放，可直接编辑，改完即生效）：
 *   /data/adb/QQIME/katxt      颜文字词库
 *   /data/adb/QQIME/whitelist  包名白名单（默认 QQ / TIM）
 *
 * 只在白名单包名的输入框上工作；不读剪贴板、不发送消息。
 */
public final class Probe implements IXposedHookLoadPackage {

    private static final String TAG = "QQCatIME";

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_MS = 500;

    /** 内容连续 N 轮不变才动手，避免打断输入法拼字。 */
    private static final int SETTLE_POLLS = 2;

    private static volatile InputMethodService service;

    /** EditorInfo.IME_ACTION_SEND —— 录音自动发送时输入法就调这个。 */
    private static final int IME_ACTION_SEND = 4;

    /** 已经钩过 send 的 InputConnection 类，按类名去重。 */
    private static final Set<String> hookedIc = Collections.synchronizedSet(new HashSet<String>());

    /** 防止改写过程中递归触发自己。 */
    private static final AtomicBoolean inSendTransform = new AtomicBoolean(false);

    /** 输入法是否正在拼写/语音识别中 —— 此时绝不能干预。 */
    private static volatile boolean icComposing = false;
    private static volatile long icComposingAt = 0L;

    /** 组合结束后的静默期：避免轮询与输入法抢写。 */
    private static final long COMPOSE_QUIET_MS = 1200;

    /** 拼写标志超过此时长未刷新，即视为失效（兜底，防卡死）。 */
    private static final long COMPOSE_STALE_MS = 3000;

    /**
     * 是否应当让路给输入法。
     *
     * 关键：icComposing 必须有超时兜底 —— 若输入法没调 finishComposingText
     * 就直接 commit，标志位会永久为 true，导致轮询彻底停摆。
     */
    private static boolean composingQuiet() {
        long age = System.currentTimeMillis() - icComposingAt;
        if (icComposing && age < COMPOSE_STALE_MS) {
            return true;   // 确实正在拼写
        }
        if (age < COMPOSE_QUIET_MS) {
            return true;   // 刚结束的静默期
        }
        return false;
    }

    private static final AtomicBoolean workerStarted = new AtomicBoolean();

    static void log(String s) {
        Log.i(TAG, s);
        XposedBridge.log(TAG + " " + s);
        Cat.log(s);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!"com.tencent.wetype".equals(pkg.packageName)) {
            return;
        }
        log("LOADED pkg=" + pkg.packageName + " proc=" + pkg.processName
                + " uid=" + Process.myUid());

        try {
            Cat.loadKaomoji();
            Cat.loadWhitelist();
            log("配置就绪：词库 " + Cat.kaomojiCount() + " 条，白名单 "
                    + Cat.whitelist().size() + " 个 " + Cat.whitelist());
        } catch (Throwable t) {
            log("配置加载失败 " + t.getClass().getSimpleName());
        }

        hookAboutActivity();
        hookSend();
        hookService("onCreate", new Class<?>[0]);
        hookService("onStartInput", new Class<?>[]{EditorInfo.class, boolean.class});
        hookService("onStartInputView", new Class<?>[]{EditorInfo.class, boolean.class});

        startWorker();
    }

    /**
     * 钩微信输入法「关于」页，注入配置面板。
     * 布局 id 或类名变化时静默跳过，不影响输入法正常使用。
     */
    /**
     * 拦截「发送」，在真正发送之前把输入框文本喵化。
     *
     * 语音输入的时序（实测）：
     *   setComposingText(全文) -> finishComposingText -> performEditorAction(4)
     * 三步之间只隔 5 毫秒，500ms 的轮询根本来不及，
     * 所以必须在 performEditorAction 这个「发送动作」里抢先改写。
     */
    private void hookSend() {
        // 输入法取 IC 时，顺便钩住它的 performEditorAction / sendKeyEvent
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class,
                    "getCurrentInputConnection", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object ic = p.getResult();
                            if (ic instanceof InputConnection) {
                                hookIcSend((InputConnection) ic);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("SEND_HOOK_FAIL " + t.getClass().getSimpleName());
        }

        // 兜底：直接钩 fineComposingText 之后的 SEND
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class,
                    "sendDefaultEditorAction", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            transformBeforeSend(null);
                        }
                    });
        } catch (Throwable t) {
            log("SEND_DEFAULT_HOOK_FAIL " + t.getClass().getSimpleName());
        }
    }

    /** 钩某个 InputConnection 的发送方法。 */
    private static void hookIcSend(InputConnection ic) {
        Class<?> c = ic.getClass();
        String n = c.getName();
        if (!hookedIc.add(n)) {
            return;
        }
        try {
            // 语音识别期间全程 setComposingText，此时必须让路
            XposedBridge.hookAllMethods(c, "setComposingText", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    icComposing = true;
                    icComposingAt = System.currentTimeMillis();
                }
            });
            // 定稿 —— 距自动发送只有几毫秒，这是真正的「发送前」
            XposedBridge.hookAllMethods(c, "finishComposingText", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    icComposing = false;
                    icComposingAt = System.currentTimeMillis();
                    transformBeforeSend((InputConnection) p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(c, "performEditorAction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Integer action = (p.args != null && p.args.length > 0 && p.args[0] instanceof Integer)
                            ? (Integer) p.args[0] : -1;
                    if (action != null && action == IME_ACTION_SEND) {
                        transformBeforeSend((InputConnection) p.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(c, "sendKeyEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args != null && p.args.length > 0
                            && p.args[0] instanceof android.view.KeyEvent) {
                        android.view.KeyEvent ev = (android.view.KeyEvent) p.args[0];
                        if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                                && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                            transformBeforeSend((InputConnection) p.thisObject);
                        }
                    }
                }
            });
            log("SEND_HOOKED " + n);
        } catch (Throwable t) {
            log("SEND_HOOK_ERR " + t.getClass().getSimpleName());
        }
    }

    /**
     * 发送前的文本改写。用 CAS 防重入（避免改写时又触发 setComposingText 回调）。
     */
    private static void transformBeforeSend(InputConnection hint) {
        if (!inSendTransform.compareAndSet(false, true)) {
            return;
        }
        try {
            InputMethodService svc = service;
            if (svc == null) {
                return;
            }
            InputConnection ic = hint != null ? hint : svc.getCurrentInputConnection();
            if (ic == null) {
                return;
            }
            // 白名单校验
            EditorInfo ei = svc.getCurrentInputEditorInfo();
            String host = (ei == null || ei.packageName == null) ? null : ei.packageName.toString();
            if (host == null || !Cat.allowed(host)) {
                return;
            }
            Cat.loadKaomoji();
            Cat.loadWhitelist();

            // 取全部文本（游标前后都要，因为语音上屏后光标位置不定）
            CharSequence beforeCs = ic.getTextBeforeCursor(2000, 0);
            CharSequence afterCs = ic.getTextAfterCursor(2000, 0);
            String before = beforeCs == null ? "" : beforeCs.toString();
            String after = afterCs == null ? "" : afterCs.toString();
            String cur = before + after;
            if (cur.length() == 0) {
                return;
            }
            // 语音识别常无句末标点，用宽松模式：人称替换 + 颜文字必定执行
            String want = Cat.transformLoose(cur);
            if (want.equals(cur)) {
                return;
            }
            // 全量替换：先删光标前全部，再删光标后全部，最后写入
            if (before.length() > 0) {
                ic.deleteSurroundingText(before.length(), 0);
            }
            if (after.length() > 0) {
                ic.deleteSurroundingText(0, after.length());
            }
            boolean ok = ic.commitText(want, 1);
            log("SEND_TRANSFORM host=" + host + " ok=" + ok
                    + " from=[" + cur + "] to=[" + want + "]");
        } catch (Throwable t) {
            log("SEND_TRANSFORM_ERR " + t.getClass().getSimpleName());
        } finally {
            inSendTransform.set(false);
        }
    }

    private void hookAboutActivity() {
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object self = param.thisObject;
                            if (!(self instanceof android.app.Activity)) {
                                return;
                            }
                            android.app.Activity act = (android.app.Activity) self;
                            String name = act.getClass().getName();
                            if (name.endsWith("ImeAboutActivity")) {
                                log("UI_TARGET_HIT " + name);
                                ConfigUI.inject(act);
                            }
                        }
                    });
            log("UI_HOOKED Activity.onCreate (按类名后缀匹配)");
        } catch (Throwable t) {
            log("UI_HOOK_FAIL " + t.getClass().getSimpleName());
        }
    }

    private void hookService(String name, Class<?>[] params) {
        try {
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (self instanceof InputMethodService) {
                        service = (InputMethodService) self;
                    }
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof EditorInfo) {
                        EditorInfo ei = (EditorInfo) param.args[0];
                        log("CALL " + name + " host=" + (ei == null ? "-" : ei.packageName));
                    }
                }
            };
            if (params.length == 0) {
                XposedHelpers.findAndHookMethod(InputMethodService.class, name, cb);
            } else {
                XposedHelpers.findAndHookMethod(InputMethodService.class, name,
                        params[0], params[1], cb);
            }
        } catch (Throwable t) {
            log("HOOK_FAIL " + name + " " + t.getClass().getSimpleName());
        }
    }

    /** 主循环：轮询输入框，检测句末标点并改写。 */
    private void startWorker() {
        if (!workerStarted.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String lastSeen = null;
                String pending = null;
                String lastHost = null;
                int stable = 0;

                while (true) {
                    try {
                        InputMethodService svc = service;
                        if (svc == null) {
                            Thread.sleep(POLL_MS);
                            continue;
                        }

                        Cat.loadKaomoji();
                        Cat.loadWhitelist();
                        Cat.loadSeen();

                        EditorInfo ei = svc.getCurrentInputEditorInfo();
                        String host = (ei == null || ei.packageName == null)
                                ? null : ei.packageName.toString();
                        boolean allowed = host != null && Cat.allowed(host);

                        // 输入法正在拼写/语音识别中 —— 让路，绝不干预，
                        // 否则会被随后的 setComposingText 覆盖，导致文本重复。
                        if (composingQuiet()) {
                            pending = null;
                            stable = 0;
                            Thread.sleep(POLL_MS);
                            continue;
                        }

                        // 自动发现：只要输入法碰到了某个 App 的输入框，
                        // 就记下来（包括那些枚举不到、没有 MAIN 入口的应用）。
                        if (host != null) {
                            Cat.noteSeen(host);
                        }

                        // 切换 App 时重置基线：新输入框里的内容属于「既有文本」，
                        // 不应当作本次新输入而触发改写。
                        if (host == null || !host.equals(lastHost)) {
                            lastHost = host;
                            lastSeen = null;
                            pending = null;
                            stable = 0;
                        }

                        InputConnection ic = svc.getCurrentInputConnection();
                        String cur = null;
                        if (ic != null) {
                            try {
                                CharSequence before = ic.getTextBeforeCursor(500, 0);
                                cur = before == null ? "" : before.toString();
                            } catch (Throwable e) {
                                cur = null;
                            }
                        }

                        if (!allowed || cur == null || cur.length() == 0) {
                            pending = null;
                            stable = 0;
                            lastSeen = cur;
                            Thread.sleep(POLL_MS);
                            continue;
                        }

                        if (cur.equals(lastSeen)) {
                            Thread.sleep(POLL_MS);
                            continue;
                        }

                        String want = Cat.transform(cur);
                        if (want.equals(cur)) {
                            lastSeen = cur;
                            pending = null;
                            stable = 0;
                        } else if (cur.equals(pending)) {
                            stable++;
                            if (stable >= SETTLE_POLLS) {
                                boolean ok = writeBack(ic, cur, want);
                                log("WRITE host=" + host + " ok=" + ok
                                        + " from=[" + cur + "] to=[" + want + "]");
                                lastSeen = ok ? want : cur;
                                pending = null;
                                stable = 0;
                            }
                        } else {
                            pending = cur;
                            stable = 0;
                        }
                    } catch (Throwable e) {
                        log("LOOP_ERR " + e.getClass().getSimpleName());
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "qqcat-ime-worker");
        t.setDaemon(true);
        t.start();
        log("worker started");
    }

    /**
     * 用 InputConnection 全量替换输入框文本。
     * 先清 composing 状态，再删掉旧内容，最后写入新内容。
     */
    private static boolean writeBack(InputConnection ic, String oldText, String newText) {
        try {
            try {
                ic.setComposingText("", 1);
            } catch (Throwable t) {
                // 部分实现不支持，忽略
            }
            try {
                ic.deleteSurroundingText(oldText.length(), 0);
            } catch (Throwable t) {
                // 删除失败则退化为追加
            }
            boolean ok = ic.commitText(newText, 1);
            try {
                ic.setComposingText("", 1);
            } catch (Throwable t) {
                // 忽略
            }
            return ok;
        } catch (Throwable t) {
            log("WRITE_ERR " + t.getClass().getSimpleName());
            return false;
        }
    }
}
