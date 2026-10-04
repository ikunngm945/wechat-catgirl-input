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
import android.widget.EditText;
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

        // ---- 转换设置（开关 / 后缀 / 替换规则）----
        TextView setTitle = new TextView(a);
        setTitle.setText("\n转换设置");
        setTitle.setTextSize(14);
        setTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(setTitle);

        final LinearLayout setBox = new LinearLayout(a);
        setBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(setBox);

        final Runnable refreshSettings = new Runnable() {
            @Override
            public void run() {
                setBox.removeAllViews();
                Config.load();

                // 三个总开关 + 所有应用
                setBox.addView(buildSwitchRow(a, "文字替换（我→本喵 等）",
                        Config.replaceEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(v, Config.suffix(),
                                        Config.suffixEnabled(),
                                        Config.kaomojiEnabled(),
                                        Config.allAppsEnabled());
                            }
                        }));

                // 后缀：文本框 + 开关
                LinearLayout sufRow = new LinearLayout(a);
                sufRow.setOrientation(LinearLayout.HORIZONTAL);
                sufRow.setGravity(Gravity.CENTER_VERTICAL);
                sufRow.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
                TextView sufLab = new TextView(a);
                sufLab.setText("句末后缀：");
                sufLab.setTextSize(13);
                sufRow.addView(sufLab);
                final EditText sufInput = new EditText(a);
                sufInput.setTextSize(13);
                sufInput.setText(Config.suffix());
                sufInput.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                sufRow.addView(sufInput);
                Button sufSave = new Button(a);
                sufSave.setText("保存后缀");
                sufSave.setTextSize(11);
                sufSave.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Config.saveSettings(Config.replaceEnabled(),
                                sufInput.getText().toString(),
                                Config.suffixEnabled(),
                                Config.kaomojiEnabled(),
                                Config.allAppsEnabled());
                        toast(a, "后缀已保存：" + Config.suffix());
                    }
                });
                sufRow.addView(sufSave);
                setBox.addView(sufRow);

                setBox.addView(buildSwitchRow(a, "启用句末后缀",
                        Config.suffixEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(Config.replaceEnabled(),
                                        Config.suffix(), v,
                                        Config.kaomojiEnabled(),
                                        Config.allAppsEnabled());
                            }
                        }));

                setBox.addView(buildSwitchRow(a, "启用颜文字",
                        Config.kaomojiEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(Config.replaceEnabled(),
                                        Config.suffix(), Config.suffixEnabled(),
                                        v, Config.allAppsEnabled());
                            }
                        }));

                setBox.addView(buildSwitchRow(a, "对所有应用生效（关闭则按白名单）",
                        Config.allAppsEnabled(), new SwitchHandler() {
                            @Override
                            public void onSet(boolean v) {
                                Config.saveSettings(Config.replaceEnabled(),
                                        Config.suffix(), Config.suffixEnabled(),
                                        Config.kaomojiEnabled(), v);
                            }
                        }));

                // 替换规则编辑
                TextView rLab = new TextView(a);
                rLab.setTextSize(13);
                rLab.setTextColor(Color.parseColor("#111111"));
                rLab.setText("\n文字替换规则（每行一条：原词=替换词）\n"
                        + "行首加 ! 可单独关闭该条，例如 !你=主人");
                setBox.addView(rLab);

                final EditText rInput = new EditText(a);
                rInput.setTextSize(12);
                rInput.setGravity(Gravity.TOP | Gravity.START);
                StringBuilder rb = new StringBuilder();
                for (Config.Rule r : Config.rules()) {
                    rb.append(r.line()).append('\n');
                }
                rInput.setText(rb.toString());
                rInput.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (110 * d)));
                setBox.addView(rInput);

                Button rSave = new Button(a);
                rSave.setText("保存替换规则");
                rSave.setTextSize(11);
                rSave.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Config.saveRules(rInput.getText().toString());
                        toast(a, "已保存 " + Config.enabledRuleCount() + " 条启用规则");
                    }
                });
                setBox.addView(rSave);

                TextView rHint = new TextView(a);
                rHint.setTextSize(10);
                rHint.setTextColor(Color.parseColor("#888888"));
                rHint.setText("当前设置：替换 " + (Config.replaceEnabled() ? "开" : "关")
                        + " / 后缀 " + (Config.suffixEnabled()
                                ? "[" + Config.suffix() + "]" : "关")
                        + " / 颜文字 " + (Config.kaomojiEnabled() ? "开" : "关")
                        + " / 全部应用 " + (Config.allAppsEnabled() ? "开" : "关"));
                setBox.addView(rHint);
            }
        };
        refreshSettings.run();

        // ---- 使用说明 ----
        TextView helpTitle = new TextView(a);
        helpTitle.setText("\n使用说明");
        helpTitle.setTextSize(14);
        helpTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(helpTitle);

        TextView help = new TextView(a);
        help.setTextSize(12);
        help.setTextColor(Color.parseColor("#444444"));
        help.setText(
            "本模块让微信 / QQ 聊天时自动喵化：\n"
          + "  我 → 本喵，你 → 主人，句末加「喵」，末尾随机颜文字。\n"
          + "\n"
          + "【三种触发方式，满足任一即可】\n"
          + "  1. 语音转文字：说完自动发送前改写，直接触发。\n"
          + "  2. 打标点：输入到 。！？ 等标点后停顿约 2 秒，自动改写。\n"
          + "  3. 输入法发送键：点微信输入法的「发送」触发。\n"
          + "     微信 / QQ 需先开启：设置 → 辅助输入 → 回车键发送。\n"
          + "\n"
          + "【使用前检查】\n"
          + "  · 先在下面「白名单」里勾选要生效的应用（如微信）。\n"
          + "  · 改完词库或规则立即生效，无需重启输入法。\n"
          + "  · 不生效时，点最下方「复制全部日志」发给开发者排查。"
        );
        help.setTextIsSelectable(true);
        root.addView(help);

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

        // ---- 标签与词库编辑 ----
        TextView tagTitle = new TextView(a);
        tagTitle.setText("\n标签与词库（点标签名编辑颜文字和关键词）");
        tagTitle.setTextSize(14);
        tagTitle.setTextColor(Color.parseColor("#111111"));
        root.addView(tagTitle);

        final LinearLayout tagList = new LinearLayout(a);
        tagList.setOrientation(LinearLayout.VERTICAL);
        root.addView(tagList);

        final LinearLayout masterInfo = new LinearLayout(a);
        masterInfo.setOrientation(LinearLayout.VERTICAL);
        root.addView(masterInfo);

        // 刷新标签列表（编辑后调用）
        final Runnable refreshTags = new Runnable() {
            @Override
            public void run() {
                tagList.removeAllViews();
                masterInfo.removeAllViews();
                for (final String tg : TagLib.tagNames()) {
                    tagList.addView(buildTagRow(a, tg, wlView, null));
                }
                TextView mi = new TextView(a);
                mi.setTextSize(11);
                mi.setTextColor(Color.parseColor("#666666"));
                mi.setText("总库 katxt：" + Cat.kaomojiCount()
                        + " 条（由所有标签自动汇总去重）");
                masterInfo.addView(mi);
            }
        };
        // 用可复用的 holder 让行内能触发刷新
        for (final String tg : TagLib.tagNames()) {
            tagList.addView(buildTagRow(a, tg, wlView, refreshTags));
        }

        // 新建标签
        LinearLayout newTagRow = new LinearLayout(a);
        newTagRow.setOrientation(LinearLayout.HORIZONTAL);
        newTagRow.setPadding(0, (int) (8 * d), 0, 0);
        final EditText newTagInput = new EditText(a);
        newTagInput.setHint("新标签名，如 得意");
        newTagInput.setTextSize(12);
        newTagInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        newTagRow.addView(newTagInput);
        Button newTagBtn = new Button(a);
        newTagBtn.setText("新建");
        newTagBtn.setTextSize(12);
        newTagBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = newTagInput.getText().toString().trim();
                if (name.length() == 0) {
                    toast(a, "请先输入标签名");
                    return;
                }
                if (TagLib.createTag(name)) {
                    newTagInput.setText("");
                    refreshTags.run();
                    toast(a, "已新建标签：" + name);
                } else {
                    toast(a, "标签已存在或名称无效");
                }
            }
        });
        newTagRow.addView(newTagBtn);
        root.addView(newTagRow);

        // ---- 恢复默认设置 ----
        Button restoreBtn = new Button(a);
        restoreBtn.setText("恢复默认设置");
        restoreBtn.setTextSize(12);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = (int) (10 * d);
        restoreBtn.setLayoutParams(rlp);
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(a)
                        .setTitle("恢复默认设置")
                        .setMessage("将把内置的词库、标签分类、关键词规则全部还原，"
                                + "你自行添加或修改的内容会被覆盖。\n\n"
                                + "白名单不受影响（仍保留你的勾选）。\n\n确定继续吗？")
                        .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int w) {
                                if (Cat.restoreDefaults()) {
                                    toast(a, "已恢复默认：词库 " + Cat.kaomojiCount()
                                            + " 条，标签 " + TagLib.tagCount()
                                            + " 个，规则 " + TagLib.ruleCount() + " 条");
                                    // 标签列表需要按新配置重建
                                    tagList.removeAllViews();
                                    for (String tg2 : TagLib.tagNames()) {
                                        tagList.addView(buildTagRow(a, tg2, wlView, null));
                                    }
                                    masterInfo.removeAllViews();
                                    TextView mi2 = new TextView(a);
                                    mi2.setTextSize(11);
                                    mi2.setTextColor(Color.parseColor("#666666"));
                                    mi2.setText("总库 katxt：" + Cat.kaomojiCount()
                                            + " 条（由所有标签自动汇总去重）");
                                    masterInfo.addView(mi2);
                                    wlView.setText(formatWhitelist());
                                } else {
                                    toast(a, "恢复失败，请查看日志");
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        root.addView(restoreBtn);

        TextView restoreHint = new TextView(a);
        restoreHint.setTextSize(10);
        restoreHint.setTextColor(Color.parseColor("#888888"));
        restoreHint.setText("误删配置后可用。内置默认来自打包时的 config/ 目录。");
        root.addView(restoreHint);

        TextView mi = new TextView(a);
        mi.setTextSize(11);
        mi.setTextColor(Color.parseColor("#666666"));
        mi.setText("总库 katxt：" + Cat.kaomojiCount()
                + " 条（由所有标签自动汇总去重）");
        masterInfo.addView(mi);

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

    /** 一行标签：显示名称、条数，点击进入编辑，长按删除。 */
    private static View buildTagRow(final Activity a, final String tag,
                                    final TextView wlView, final Runnable onChanged) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * d), 0, (int) (8 * d));

        TextView tv = new TextView(a);
        int kaoN = TagLib.readTagKaomoji(tag).size();
        int kwN = TagLib.readTagKeywords(tag).size();
        tv.setText("● " + tag + "    颜文字 " + kaoN + " 条 / 关键词 " + kwN + " 条");
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#0A7D32"));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTagEditor(a, tag, onChanged);
            }
        });
        row.addView(tv);

        Button del = new Button(a);
        del.setText("删除");
        del.setTextSize(11);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(a)
                        .setTitle("删除标签")
                        .setMessage("确定删除标签「" + tag + "」？\n"
                                + "它的颜文字与关键词规则都会一并移除。")
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dlg, int w) {
                                if (TagLib.deleteTag(tag)) {
                                    toast(a, "已删除：" + tag);
                                    if (onChanged != null) {
                                        onChanged.run();
                                    }
                                } else {
                                    toast(a, "删除失败");
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        row.addView(del);
        return row;
    }

    /**
     * 标签编辑对话框：上下两个文本框。
     * 上：该标签的颜文字（每行一条）
     * 下：该标签的关键词（每行一个）
     * 保存后自动重建总库 katxt（去重）。
     */
    private static void showTagEditor(final Activity a, final String tag,
                                      final Runnable onChanged) {
        try {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int pd = (int) (16 * d);
            box.setPadding(pd, pd, pd, pd);

            TextView l1 = new TextView(a);
            l1.setText("颜文字（每行一条）");
            l1.setTextSize(13);
            box.addView(l1);

            final EditText kao = new EditText(a);
            kao.setTextSize(12);
            kao.setGravity(Gravity.TOP | Gravity.START);
            kao.setMinLines(4);
            StringBuilder kb = new StringBuilder();
            for (String k : TagLib.readTagKaomoji(tag)) {
                kb.append(k).append('\n');
            }
            kao.setText(kb.toString());
            kao.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (150 * d)));
            box.addView(kao);

            TextView l2 = new TextView(a);
            l2.setText("\n关键词（每行一个，句中出现即命中本标签）");
            l2.setTextSize(13);
            box.addView(l2);

            final EditText kw = new EditText(a);
            kw.setTextSize(12);
            kw.setGravity(Gravity.TOP | Gravity.START);
            StringBuilder wb = new StringBuilder();
            for (String k : TagLib.readTagKeywords(tag)) {
                wb.append(k).append('\n');
            }
            kw.setText(wb.toString());
            kw.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (110 * d)));
            box.addView(kw);

            TextView hint = new TextView(a);
            hint.setTextSize(10);
            hint.setTextColor(Color.parseColor("#888888"));
            hint.setText("保存后立即生效，总库 katxt 会自动按所有标签汇总去重。");
            box.addView(hint);

            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(box);

            new android.app.AlertDialog.Builder(a)
                    .setTitle("编辑标签：" + tag)
                    .setView(sv)
                    .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dlg, int w) {
                            boolean ok1 = TagLib.saveTagKaomoji(tag, kao.getText().toString());
                            boolean ok2 = TagLib.saveTagKeywords(tag, kw.getText().toString());
                            // 汇总去重，重建总库
                            int total = TagLib.rebuildMasterLibrary();
                            toast(a, (ok1 && ok2 ? "已保存" : "部分保存失败")
                                    + "，总库 " + total + " 条");
                            if (onChanged != null) {
                                onChanged.run();
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Cat.log("标签编辑失败 " + t.getClass().getSimpleName());
            toast(a, "打开编辑器失败：" + t.getClass().getSimpleName());
        }
    }

    /** 开关变更回调。 */
    private interface SwitchHandler {
        void onSet(boolean v);
    }

    /** 构造一行「标题 + 开关」。 */
    private static View buildSwitchRow(Activity a, String label,
                                       boolean checked,
                                       final SwitchHandler h) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        h.onSet(v);
                    }
                });
        row.addView(sw);
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
