package org.dsh.qqcatime;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 在微信输入法「关于」页注入的配置面板。
 *
 * 显示词库条数 + 白名单列表，并可从「输入法可见的应用」中勾选加入白名单。
 * 因为界面跑在输入法进程内，写白名单文件不需要 root。
 */
public final class ConfigUI {

    /** 面板 id，用于防止重复注入。 */
    public static final int PANEL_ID = 0x7f0f0001;

    private ConfigUI() {
    }

    /** 注入面板；延迟到布局挂载后执行，找不到容器时静默跳过。 */
    public static void inject(final Activity activity) {
        try {
            if (activity.findViewById(PANEL_ID) != null) {
                return;
            }
            // onCreate 时 content 尚未挂载子视图，必须 post 到消息队列末尾
            final View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) {
                Cat.log("UI: 无 decorView，跳过");
                return;
            }
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.findViewById(PANEL_ID) != null) {
                            return;
                        }
                        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
                        if (content == null) {
                            Cat.log("UI: content 为 null");
                            return;
                        }
                        View panel = buildPanel(activity);
                        content.addView(panel, new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                        Cat.log("UI: 面板已插入，content children=" + content.getChildCount());
                    } catch (Throwable t) {
                        Cat.log("UI: 注入失败 " + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            Cat.log("UI: 异常 " + t.getClass().getSimpleName());
        }
    }

    private static View buildPanel(final Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(a);
        root.setId(PANEL_ID);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#F5F5F5"));

        // 标题
        TextView title = new TextView(a);
        title.setText("微信输入法猫娘模块");
        title.setTextSize(16);
        title.setTextColor(Color.parseColor("#111111"));
        title.setPadding(0, 0, 0, (int) (8 * d));
        root.addView(title);

        // 词库信息
        Cat.loadKaomoji();
        Cat.loadWhitelist();
        TagLib.load();
        TextView kao = new TextView(a);
        kao.setText("颜文字词库：" + Cat.kaomojiCount() + " 条\n"
                + "标签分类：" + TagLib.tagCount() + " 个\n"
                + "关键词规则：" + TagLib.ruleCount() + " 条\n"
                + "词库文件：" + Cat.KAO_FILE + "\n"
                + "标签目录：" + TagLib.TAGS_DIR + "\n"
                + "规则文件：" + TagLib.RULES_FILE);
        kao.setTextSize(12);
        kao.setTextColor(Color.parseColor("#666666"));
        root.addView(kao);

        // 白名单标题
        TextView wlTitle = new TextView(a);
        wlTitle.setText("\n白名单（勾选后可自动喵化）");
        wlTitle.setTextSize(14);
        wlTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(wlTitle);

        // 当前白名单
        final TextView wlView = new TextView(a);
        wlView.setTextSize(12);
        wlView.setTextColor(Color.parseColor("#0A7D32"));
        wlView.setText(formatWhitelist());
        root.addView(wlView);

        // 可见应用列表（可勾选）
        List<AppEntry> apps = visibleApps(a);
        TextView appsTitle = new TextView(a);
        appsTitle.setText("\n可添加的应用（输入法可见 " + apps.size() + " 个）");
        appsTitle.setTextSize(14);
        appsTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(appsTitle);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (AppEntry e : apps) {
            list.addView(buildRow(a, e, wlView));
        }
        root.addView(list);

        // ---- 自动发现的应用（输入法遇到过、且不在可见列表里）----
        Cat.loadSeen();
        java.util.Set<String> visible = new java.util.HashSet<String>();
        for (AppEntry e : apps) {
            visible.add(e.pkg);
        }
        List<String> seen = new ArrayList<String>();
        for (String p : Cat.seenList()) {
            if (!visible.contains(p)) {
                seen.add(p);
            }
        }
        Cat.log("UI: 发现列表 " + seen.size() + " 个 " + seen
                + "（原始 " + Cat.seenCount() + "，可见 " + visible.size() + "）");
        if (!seen.isEmpty()) {
            TextView seenTitle = new TextView(a);
            seenTitle.setText("\n自动发现的应用（" + seen.size()
                    + " 个，来自实际输入记录）");
            seenTitle.setTextSize(14);
            seenTitle.setTextColor(Color.parseColor("#111111"));
            root.addView(seenTitle);

            TextView seenHint = new TextView(a);
            seenHint.setText("这些应用没有可枚举的入口，但输入法在它们里面出现过；"
                    + "勾选即加入白名单。");
            seenHint.setTextSize(11);
            seenHint.setTextColor(Color.parseColor("#888888"));
            root.addView(seenHint);

            LinearLayout seenList = new LinearLayout(a);
            seenList.setOrientation(LinearLayout.VERTICAL);
            for (String pkg : seen) {
                seenList.addView(buildSeenRow(a, pkg, wlView));
            }
            root.addView(seenList);

            TextView clear = new TextView(a);
            clear.setText("清空发现记录");
            clear.setTextSize(12);
            clear.setTextColor(Color.parseColor("#C0392B"));
            clear.setPadding(0, (int) (8 * d), 0, 0);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Cat.clearSeen();
                    wlView.setText(formatWhitelist());
                    try {
                        android.widget.Toast.makeText(a, "已清空，重新打开本页生效",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        // 忽略
                    }
                }
            });
            root.addView(clear);
        }

        // ---- 日志 ----
        TextView logTitle = new TextView(a);
        logTitle.setText("\n日志（可长按复制后发给开发者）");
        logTitle.setTextSize(14);
        logTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(logTitle);

        final TextView logInfo = new TextView(a);
        logInfo.setTextSize(11);
        logInfo.setTextColor(Color.parseColor("#666666"));
        String shown = Cat.readLogForDisplay();
        logInfo.setText(shown);
        logInfo.setTextIsSelectable(true);   // 允许长按选中复制

        // 日志正文放进一个限高可滚动的框里，避免撑爆面板
        android.widget.ScrollView logBox = new android.widget.ScrollView(a);
        int boxH = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.22f);
        logBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, boxH));
        logBox.setBackgroundColor(Color.parseColor("#FFFFFF"));
        int p8 = (int) (8 * d);
        logBox.setPadding(p8, p8, p8, p8);
        logBox.addView(logInfo);
        root.addView(logBox);

        LinearLayout logBtns = new LinearLayout(a);
        logBtns.setOrientation(LinearLayout.HORIZONTAL);
        logBtns.setPadding(0, (int) (8 * d), 0, 0);

        Button copyBtn = new Button(a);
        copyBtn.setText("复制全部日志");
        copyBtn.setTextSize(12);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    String full = Cat.buildLogReport("用户复制");
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "QQ猫娘日志", full));
                        toast(a, "日志已复制，去聊天框长按粘贴即可发送");
                    }
                } catch (Throwable t) {
                    toast(a, "复制失败：" + t.getClass().getSimpleName());
                }
                logInfo.setText(Cat.readLogForDisplay());
            }
        });
        logBtns.addView(copyBtn);

        Button clearBtn = new Button(a);
        clearBtn.setText("清空");
        clearBtn.setTextSize(12);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Cat.clearLog();
                logInfo.setText(Cat.readLogForDisplay());
                toast(a, "日志已清空");
            }
        });
        logBtns.addView(clearBtn);
        root.addView(logBtns);

        TextView logHint = new TextView(a);
        logHint.setText("点「复制全部日志」，粘贴到任意聊天框发送给开发者即可。"
                + "日志只记录运行状态，不含聊天内容。");
        logHint.setTextSize(10);
        logHint.setTextColor(Color.parseColor("#888888"));
        root.addView(logHint);

        // 提示
        TextView tip = new TextView(a);
        tip.setText("\n改动立即生效，无需重启输入法。");
        tip.setTextSize(11);
        tip.setTextColor(Color.parseColor("#888888"));
        root.addView(tip);

        ScrollView sv = new ScrollView(a);
        sv.addView(root);
        // 限高，避免把输入法布局撑坏；内容可在面板内滚动
        int h = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return sv;
    }

    /** 已发现应用的勾选行（可能拿不到图标，用包名兜底）。 */
    private static View buildSeenRow(final Activity a, final String pkg,
                                     final TextView wlView) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));

        String label = pkg;
        Drawable icon = null;
        try {
            android.content.pm.PackageManager pm = a.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = String.valueOf(pm.getApplicationLabel(ai));
            icon = pm.getApplicationIcon(ai);
        } catch (Throwable t) {
            // 拿不到就只显示包名
        }
        if (icon != null) {
            ImageView iv = new ImageView(a);
            int sz = (int) (32 * d);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setImageDrawable(icon);
            row.addView(iv);
        }

        final CheckBox cb = new CheckBox(a);
        cb.setText(label + "\n" + pkg);
        cb.setTextSize(12);
        cb.setChecked(Cat.allowed(pkg));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Cat.setWhitelisted(pkg, checked);
                Cat.log("UI: 发现项 " + (checked ? "添加 " : "移除 ") + pkg);
                wlView.setText(formatWhitelist());
            }
        });
        row.addView(cb);
        return row;
    }

    private static View buildRow(final Activity a, final AppEntry e, final TextView wlView) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));

        if (e.icon != null) {
            ImageView iv = new ImageView(a);
            int sz = (int) (32 * d);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setImageDrawable(e.icon);
            row.addView(iv);
        }

        final CheckBox cb = new CheckBox(a);
        cb.setText(e.label + "\n" + e.pkg);
        cb.setTextSize(12);
        cb.setChecked(Cat.allowed(e.pkg));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                boolean ok = Cat.setWhitelisted(e.pkg, checked);
                Cat.log("UI: " + (checked ? "添加" : "移除") + " " + e.pkg + " ok=" + ok);
                wlView.setText(formatWhitelist());
            }
        });
        row.addView(cb);
        return row;
    }

    /** 日志状态描述。 */
    private static String logStat() {
        try {
            java.io.File f = new java.io.File(Cat.LOG_FILE);
            long len = f.isFile() ? f.length() : 0;
            return "当前日志 " + (len / 1024) + " KB\n路径：" + Cat.LOG_FILE;
        } catch (Throwable t) {
            return "日志不可读";
        }
    }

    /** 弹一个 Toast，失败时静默。 */
    private static void toast(Activity a, String msg) {
        try {
            android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            // 忽略
        }
    }

    private static String formatWhitelist() {
        List<String> wl = Cat.whitelist();
        StringBuilder sb = new StringBuilder();
        for (String s : wl) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("  • ").append(s);
        }
        return sb.toString();
    }

    /** 取输入法可见、且有启动入口的应用。 */
    private static List<AppEntry> visibleApps(Activity a) {
        List<AppEntry> out = new ArrayList<AppEntry>();
        try {
            PackageManager pm = a.getPackageManager();
            // 只查 ACTION_MAIN、不加 CATEGORY_LAUNCHER：
            // 实测可见应用从 31 个提升到 72 个（含无桌面图标但有入口的应用）。
            android.content.Intent i =
                    new android.content.Intent(android.content.Intent.ACTION_MAIN);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || !seen.add(pkg)) {
                    continue;
                }
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    e.label = String.valueOf(pm.getApplicationLabel(ai));
                    e.icon = pm.getApplicationIcon(ai);
                } catch (Throwable t) {
                    e.label = pkg;
                }
                out.add(e);
            }
        } catch (Throwable t) {
            Cat.log("UI: 应用列表失败 " + t.getClass().getSimpleName());
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry x, AppEntry y) {
                return x.label.compareToIgnoreCase(y.label);
            }
        });
        return out;
    }

    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
    }
}
