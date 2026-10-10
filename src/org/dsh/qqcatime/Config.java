package org.dsh.qqcatime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户可配置项（关于页可改）。
 *
 * <p>两个配置文件，都在 {@link Cat#DIR} 下：
 * <pre>
 *   replace.txt   文字替换规则，一行一条，格式「原词=替换词」，如：
 *                 我=本喵
 *                 你=主人
 *                 宝宝=宝贝
 *                 # 以 # 开头是注释；某条前面加 ! 表示单独关闭这条
 *                 !你=主人
 *
 *   settings.txt  开关与后缀，格式「键=值」：
 *                 replace=on          文字替换总开关
 *                 suffix=喵           句末后缀（可改成任意文字，留空则不加）
 *                 suffix_on=on        句末后缀开关
 *                 kaomoji=on          颜文字开关
 *                 all_apps=off        是否对所有应用生效（off 时按白名单）
 * </pre>
 */
public final class Config {

    /** 替换规则文件。 */
    public static final String REPLACE_FILE = Cat.DIR + "/replace.txt";

    /** 开关与后缀文件。 */
    public static final String SETTINGS_FILE = Cat.DIR + "/settings.txt";

    /** 默认替换规则。 */
    public static final String DEFAULT_REPLACE =
            "我=本喵\n"
          + "你=主人\n";

    /** 默认设置。 */
    public static final String DEFAULT_SETTINGS =
            "# 猫娘模块总开关（on/off）：off = 整个模块不生效，\n"
          + "# 既不替换文字也不加后缀 / 颜文字，更不请求大模型与向量。\n"
          + "master=on\n"
          + "# 文字替换总开关（on/off）\n"
          + "replace=on\n"
          + "# 句末后缀，可改成任意文字；留空则不加\n"
          + "suffix=喵\n"
          + "# 句末后缀开关（on/off）\n"
          + "suffix_on=on\n"
          + "# 颜文字开关（on/off）\n"
          + "kaomoji=on\n"
          + "# 是否对所有应用生效（off 时按白名单勾选）\n"
          + "all_apps=off\n";

    /** 一条替换规则。 */
    public static final class Rule {
        public String from;
        public String to;
        public boolean enabled;

        Rule(String from, String to, boolean enabled) {
            this.from = from;
            this.to = to;
            this.enabled = enabled;
        }

        /** 用于界面显示的一行。 */
        public String line() {
            return (enabled ? "" : "!") + from + "=" + to;
        }
    }

    private static List<Rule> rules = new ArrayList<Rule>();
    private static long rulesStamp = -2;

    private static boolean masterOn = true;
    private static boolean replaceOn = true;
    private static String suffix = "喵";
    private static boolean suffixOn = true;
    private static boolean kaomojiOn = true;
    private static boolean allApps = false;
    private static long settingsStamp = -2;

    private Config() {
    }

    // ------------------------------------------------------------ 加载

    /** 加载两个配置文件（有变化才读盘）。 */
    public static synchronized void load() {
        loadRules();
        loadSettings();
    }

    private static void loadRules() {
        File f = new File(REPLACE_FILE);
        long stamp = stamp(f);
        if (stamp == rulesStamp) {
            return;
        }
        rulesStamp = stamp;

        if (stamp == -1L || f.length() == 0) {
            Cat.writeFile(REPLACE_FILE, DEFAULT_REPLACE);
            rules = parseRules(DEFAULT_REPLACE);
            Cat.log("replace: 已释放默认 " + rules.size() + " 条");
            rulesStamp = stamp(new File(REPLACE_FILE));
            return;
        }
        List<String> lines = readLines(f);
        List<Rule> out = new ArrayList<Rule>();
        for (String line : lines) {
            Rule r = parseRule(line);
            if (r != null) {
                out.add(r);
            }
        }
        if (out.isEmpty()) {
            out = parseRules(DEFAULT_REPLACE);
        }
        rules = out;
        Cat.log("replace: 已加载 " + rules.size() + " 条（启用 "
                + enabledRuleCount() + " 条）");
    }

    private static List<Rule> parseRules(String text) {
        List<Rule> out = new ArrayList<Rule>();
        for (String line : text.split("\n")) {
            Rule r = parseRule(line);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static Rule parseRule(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.length() == 0 || line.startsWith("#")) {
            return null;
        }
        boolean enabled = true;
        if (line.startsWith("!")) {
            enabled = false;
            line = line.substring(1).trim();
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String from = line.substring(0, eq);
        String to = line.substring(eq + 1);
        if (from.length() == 0) {
            return null;
        }
        return new Rule(from, to, enabled);
    }

    private static void loadSettings() {
        File f = new File(SETTINGS_FILE);
        long stamp = stamp(f);
        if (stamp == settingsStamp) {
            return;
        }
        settingsStamp = stamp;

        if (stamp == -1L || f.length() == 0) {
            Cat.writeFile(SETTINGS_FILE, DEFAULT_SETTINGS);
        }
        // 先取默认值，再用文件里的覆盖
        boolean mOn = true, rOn = true, sOn = true, kOn = true, all = false;
        String suf = "喵";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();
                if ("master".equals(k) || "master_on".equals(k)
                        || "all_on".equals(k)) {
                    mOn = isOn(v);
                } else if ("replace".equals(k) || "replace_on".equals(k)) {
                    rOn = isOn(v);
                } else if ("suffix".equals(k)) {
                    suf = v;
                } else if ("suffix_on".equals(k)) {
                    sOn = isOn(v);
                } else if ("kaomoji".equals(k)) {
                    kOn = isOn(v);
                } else if ("all_apps".equals(k) || "allapps".equals(k)) {
                    all = isOn(v);
                }
            }
        } catch (Throwable t) {
            // 用默认值
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable t) { }
            }
        }
        masterOn = mOn;
        replaceOn = rOn;
        suffix = suf;
        suffixOn = sOn;
        kaomojiOn = kOn;
        allApps = all;
        Cat.log("settings: master=" + mOn + " replace=" + rOn + " suffix=[" + suf
                + "] suffix_on=" + sOn + " kaomoji=" + kOn + " all_apps=" + all);
    }

    private static boolean isOn(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return "on".equals(s) || "1".equals(s) || "true".equals(s)
                || "yes".equals(s) || "开".equals(s) || "启用".equals(s);
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.isFile()) {
            return out;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } catch (Throwable t) {
            // 忽略
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        return out;
    }

    private static long stamp(File f) {
        return f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
    }

    // ------------------------------------------------------------ 查询

    /** 文字替换总开关。 */
    public static boolean replaceEnabled() {
        return replaceOn;
    }

    /** 猫娘模块总开关。 */
    public static boolean masterEnabled() {
        return masterOn;
    }

    /** 句末后缀（可能为空字符串）。 */
    public static String suffix() {
        return suffix == null ? "" : suffix;
    }

    /** 句末后缀开关。 */
    public static boolean suffixEnabled() {
        return suffixOn;
    }

    /** 颜文字开关。 */
    public static boolean kaomojiEnabled() {
        return kaomojiOn;
    }

    /** 是否对所有应用生效。 */
    public static boolean allAppsEnabled() {
        return allApps;
    }

    /** 该包名是否应当生效。 */
    public static boolean shouldWorkOn(String pkg) {
        return masterOn && (allApps || Cat.allowed(pkg));
    }

    /** 启用的替换规则快照。 */
    public static List<Rule> rules() {
        return new ArrayList<Rule>(rules);
    }

    public static int enabledRuleCount() {
        int n = 0;
        for (Rule r : rules) {
            if (r.enabled) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ 保存

    /** 保存替换规则（整文件覆盖，从界面传入的多行文本）。 */
    public static synchronized boolean saveRules(String content) {
        try {
            Cat.writeFile(REPLACE_FILE, content);
            rulesStamp = -2;
            loadRules();
            Cat.log("replace: 已保存 " + rules.size() + " 条");
            return true;
        } catch (Throwable t) {
            Cat.log("保存替换规则失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 保存开关与后缀。 */
    public static synchronized boolean saveSettings(boolean rOn, String suf,
                                                    boolean sOn, boolean kOn,
                                                    boolean all) {
        return saveSettings(masterOn, rOn, suf, sOn, kOn, all);
    }

    /** 保存总开关 + 开关与后缀。 */
    public static synchronized boolean saveSettings(boolean mOn, boolean rOn,
                                                    String suf, boolean sOn,
                                                    boolean kOn, boolean all) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# 猫娘模块总开关（on/off）：off = 整个模块不生效，\n");
            sb.append("# 既不替换文字也不加后缀 / 颜文字，更不请求大模型与向量。\n");
            sb.append("master=").append(mOn ? "on" : "off").append('\n');
            sb.append("# 文字替换总开关（on/off）\n");
            sb.append("replace=").append(rOn ? "on" : "off").append('\n');
            sb.append("# 句末后缀，可改成任意文字；留空则不加\n");
            sb.append("suffix=").append(suf == null ? "" : suf).append('\n');
            sb.append("# 句末后缀开关（on/off）\n");
            sb.append("suffix_on=").append(sOn ? "on" : "off").append('\n');
            sb.append("# 颜文字开关（on/off）\n");
            sb.append("kaomoji=").append(kOn ? "on" : "off").append('\n');
            sb.append("# 是否对所有应用生效（off 时按白名单勾选）\n");
            sb.append("all_apps=").append(all ? "on" : "off").append('\n');
            Cat.writeFile(SETTINGS_FILE, sb.toString());
            settingsStamp = -2;
            loadSettings();
            return true;
        } catch (Throwable t) {
            Cat.log("保存设置失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 恢复这两项的默认。 */
    public static synchronized void restoreDefaults() {
        Cat.writeFile(REPLACE_FILE, DEFAULT_REPLACE);
        Cat.writeFile(SETTINGS_FILE, DEFAULT_SETTINGS);
        rulesStamp = -2;
        settingsStamp = -2;
        load();
        // 向量模型配置也一并还原（默认关闭，不保留用户填的 key/地址）
        Vector.restoreDefaults();
        // LLM 改写同理
        Llm.restoreDefaults();
        Cat.log("replace/settings 已恢复默认");
    }

    /** 对文本应用启用的替换规则。 */
    public static String applyReplace(String input) {
        if (!masterOn || !replaceOn || input == null || input.length() == 0) {
            return input;
        }
        String out = input;
        for (Rule r : rules) {
            if (r.enabled && r.from.length() > 0
                    && !r.from.equals(r.to)) {
                out = out.replace(r.from, r.to);
            }
        }
        return out;
    }

    /** 供界面显示：某个规则是否启用。 */
    public static boolean ruleEnabled(int index) {
        return index >= 0 && index < rules.size() && rules.get(index).enabled;
    }
}
