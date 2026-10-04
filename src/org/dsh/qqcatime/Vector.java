package org.dsh.qqcatime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 向量模型接入。
 *
 * <p>配置文件：{@code /data/user/0/com.tencent.wetype/qqime/vector.yaml}
 * （API Key 单独放在 {@code secret.yaml}，只写不可读）。
 *
 * <p>工作方式（检索链，对应用户需求）：
 * <ol>
 *   <li><b>记忆库完全相同</b>：{@link VecStore} 里已有完全一样的句子，
 *       直接输出对应颜文字，<b>不联网</b>。</li>
 *   <li><b>事前向量</b>：打字打出句末标点时，把该句丢进后台队列算向量，
 *       在 {@link KaoIndex}（颜文字语义向量索引）里找最贴切的一条，
 *       结果放进 {@link #decision(String)} 供随后的改写使用；
 *       命中后同样会把该句回写进记忆库。</li>
 *   <li><b>事后</b>：句子实际用掉某个颜文字后写进记忆库，下次直接命中。</li>
 *   <li>向量没命中（或超时/报错）时，回退到 {@link TagLib} 关键词规则，
 *       再不行才全库随机。</li>
 * </ol>
 *
 * <p>{@link KaoIndex} 只在用户点「重建向量」时生成，模块不会自动重建。
 *
 * <p>所有网络请求都在独立的后台线程上，绝不阻塞输入法主线程。
 */
public final class Vector {

    /** 配置文件（YAML），只放非敏感项。 */
    public static final String CFG_FILE = Cat.DIR + "/vector.yaml";

    /**
     * API Key 单独一个 YAML，<b>只写不可读</b>。
     *
     * <p>界面永远不回显这里的内容；用户填新 Key 就是覆盖，
     * 清空走「清除 Key」。
     */
    public static final String KEY_FILE = Cat.DIR + "/secret.yaml";

    /** 旧版 k=v 配置，仅用于一次性迁移。 */
    private static final String CFG_FILE_OLD = Cat.DIR + "/vector.cfg";

    /** 默认配置。 */
    public static final String DEFAULT_CFG =
            "# 向量模型猫娘匹配：开启后，标点触发时会先把句子交给向量模型，\n"
          + "# 在「颜文字语义向量索引」里找最贴切的一条颜文字。\n"
          + "vector: off\n"
          + "# 事前分析：off = 只做事后分析（句子用完之后记进记忆库）。\n"
          + "# on = 打字打出标点时就先请求一次，命中立刻用；\n"
          + "#      事后分析照常进行（事前包含事后）。\n"
          + "pre: on\n"
          + "# 接口地址（OpenAI 兼容）。填到 /v1 即可，会自动补 /embeddings\n"
          + "url: \"\"\n"
          + "# 模型名\n"
          + "model: \"\"\n"
          + "# 向量维度，auto = 按接口返回自动识别\n"
          + "dim: auto\n"
          + "# 相似度阈值 0~1（越大越严格）\n"
          + "min: 0.42\n"
          + "# 领先间距：第一名要比第二名高出这么多才算「真的贴切」。\n"
          + "# 190 条颜文字描述彼此很像，光看绝对分分不出「真贴切」还是\n"
          + "# 「在一堆差不多的里随便挑了个」，这个值能把准确率从 79% 提到 93%。\n"
          + "margin: 0.015\n";

    /** Key 文件的初始内容（首次使用时释放）。 */
    private static final String KEY_TEMPLATE =
            "# API Key —— 只写不可读。\n"
          + "# 界面不回显已保存的值；想换就填新的覆盖，想删用「清除 Key」。\n"
          + "api_key: \"\"\n";

    /** 后台队列里等待处理的句子上限，超出就丢弃（避免堆积）。 */
    private static final int QUEUE_MAX = 64;

    /** 一次请求的连接 / 读取超时（毫秒）。 */
    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 8000;

    // ------------------------------------------------------------ 配置状态

    private static volatile boolean enabled = false;
    /** 事前分析开关；关闭时只做事后记录。 */
    private static volatile boolean preEnabled = true;
    private static volatile String url = "";
    private static volatile String key = "";
    private static volatile String model = "";
    private static volatile String dimWant = "auto";
    private static volatile float minScore = 0.42f;
    /** 领先间距门槛：第一名减第二名要 >= 这个值才认。 */
    private static volatile float minMargin = 0.015f;
    private static long cfgStamp = -2;

    /** Key 文件的时间戳，独立跟踪。 */
    private static long keyStamp = -2;

    /** 自动识别到的维度。 */
    private static volatile int dim = 0;

    /** 上次请求的结果描述，界面上显示用。 */
    private static volatile String lastStatus = "未调用";

    private Vector() {
    }

    // ------------------------------------------------------------ 配置读写

    /** 有变化才读盘。 */
    public static synchronized void load() {
        // 记忆库与颜文字索引一起热重载：用户可能在文件管理器里直接改。
        // 两者都自带时间戳判断，没变时开销只是一次 stat。
        VecStore.load();
        KaoIndex.load();

        // 旧版 vector.cfg 一次性迁移到 yaml（迁移后旧文件删掉）。
        migrateOldConfig();

        // Key 在独立文件里，单独判时间戳。
        loadKey();

        File f = new File(CFG_FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == cfgStamp) {
            return;
        }
        cfgStamp = s;
        if (s == -1L || f.length() == 0) {
            Cat.writeFile(CFG_FILE, DEFAULT_CFG);
            cfgStamp = stampOf(CFG_FILE);
            applyDefaults();
            return;
        }
        boolean en = false;
        boolean pre = true;
        String u = "", m = "", dw = "auto";
        float ms = 0.42f;
        float mg = 0.015f;
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
                // 兼容 "key: value" 与 "key=value" 两种写法
                int sep = line.indexOf(':');
                if (sep < 0) {
                    sep = line.indexOf('=');
                }
                if (sep <= 0) {
                    continue;
                }
                String kk = line.substring(0, sep).trim().toLowerCase();
                String vv = unquote(line.substring(sep + 1).trim());
                if ("vector".equals(kk) || "vector_on".equals(kk) || "on".equals(kk)) {
                    en = isOn(vv);
                } else if ("pre".equals(kk) || "pre_on".equals(kk)
                        || "prefetch".equals(kk)) {
                    pre = isOn(vv);
                } else if ("url".equals(kk) || "endpoint".equals(kk)) {
                    u = vv;
                } else if ("key".equals(kk) || "api_key".equals(kk) || "apikey".equals(kk)) {
                    // 老配置里混着的 Key：搬进 secret.yaml，之后不再写回这里
                    if (vv.length() > 0 && !hasKey()) {
                        writeKey(vv);
                    }
                } else if ("model".equals(kk)) {
                    m = vv;
                } else if ("dim".equals(kk) || "dimension".equals(kk)) {
                    dw = vv.length() == 0 ? "auto" : vv;
                } else if ("min".equals(kk) || "threshold".equals(kk)) {
                    try {
                        ms = Float.parseFloat(vv);
                    } catch (Throwable t) {
                        // 保持默认
                    }
                } else if ("margin".equals(kk)) {
                    try {
                        mg = Float.parseFloat(vv);
                    } catch (Throwable t) {
                        // 保持默认
                    }
                }
            }
        } catch (Throwable t) {
            // 用默认值
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable t) { }
            }
        }
        enabled = en;
        preEnabled = pre;
        url = u;
        model = m;
        dimWant = dw;
        minScore = ms;
        minMargin = mg;
        if (!"auto".equalsIgnoreCase(dw)) {
            try {
                dim = Integer.parseInt(dw.trim());
            } catch (Throwable t) {
                // 忽略
            }
        }
        Cat.log("vector: 开关=" + en + " 事前=" + pre + " 模型=[" + m + "] 地址=[" + host(u)
                + "] 维度=" + dw + " 阈值=" + ms + " 间距=" + mg
                + " Key=" + (key.length() > 0 ? "已配置" : "未配置"));
    }

    // ------------------------------------------------------------ Key 文件

    /** 读 Key 文件（只进内存，永不回显到界面）。 */
    private static synchronized void loadKey() {
        File f = new File(KEY_FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == keyStamp) {
            return;
        }
        keyStamp = s;
        if (s == -1L || f.length() == 0) {
            Cat.writeFile(KEY_FILE, KEY_TEMPLATE);
            keyStamp = stampOf(KEY_FILE);
            key = "";
            return;
        }
        String found = "";
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
                int sep = line.indexOf(':');
                if (sep < 0) {
                    sep = line.indexOf('=');
                }
                if (sep <= 0) {
                    continue;
                }
                String kk = line.substring(0, sep).trim().toLowerCase();
                if ("api_key".equals(kk) || "key".equals(kk)
                        || "apikey".equals(kk) || "token".equals(kk)) {
                    found = unquote(line.substring(sep + 1).trim());
                }
            }
        } catch (Throwable t) {
            // 保持原值
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable t) { }
            }
        }
        key = found;
    }

    /** 覆盖写入 Key（空串 = 清空）。 */
    private static synchronized void writeKey(String k) {
        String body = "# API Key —— 只写不可读。\n"
                + "# 界面不回显已保存的值；想换就填新的覆盖，想删用「清除 Key」。\n"
                + "api_key: \"" + esc(k) + "\"\n";
        Cat.writeFile(KEY_FILE, body);
        keyStamp = -2;
        key = k == null ? "" : k;
    }

    /** 去掉 YAML 值两端的引号。 */
    private static String unquote(String v) {
        if (v == null || v.length() < 2) {
            return v == null ? "" : v;
        }
        char c0 = v.charAt(0);
        char c1 = v.charAt(v.length() - 1);
        if ((c0 == '"' && c1 == '"') || (c0 == '\'' && c1 == '\'')) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 旧版 vector.cfg -> vector.yaml，只做一次。 */
    private static void migrateOldConfig() {
        File old = new File(CFG_FILE_OLD);
        if (!old.exists()) {
            return;
        }
        File neu = new File(CFG_FILE);
        if (neu.exists() && neu.length() > 0) {
            old.delete();
            return;
        }
        try {
            String body = readAll(new FileInputStream(old));
            Cat.writeFile(CFG_FILE, body);
            Cat.log("vector: 已从 vector.cfg 迁移到 vector.yaml");
        } catch (Throwable t) {
            Cat.log("vector: 迁移配置失败 " + t.getClass().getSimpleName());
        }
        old.delete();
    }

    private static void applyDefaults() {
        enabled = false;
        preEnabled = true;
        url = "";
        // 注意：key 不在这里清 —— 它存在独立文件里，由 loadKey() 管。
        model = "";
        dimWant = "auto";
        minScore = 0.42f;
        minMargin = 0.015f;
        Cat.log("vector: 已释放默认配置（默认关闭）");
    }

    private static long stampOf(String path) {
        File f = new File(path);
        return f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
    }

    private static boolean isOn(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return "on".equals(s) || "1".equals(s) || "true".equals(s)
                || "yes".equals(s) || "开".equals(s) || "启用".equals(s);
    }

    /**
     * Key 的「保持原值」哨兵。
     *
     * <p>Key 是只写不可读的：界面不会回显已保存的 Key，
     * 用户留空时传这个值表示「不要动原来的 Key」。
     */
    public static final String KEEP_KEY = "\u0000keep";

    /**
     * 保存配置（界面调用）。
     *
     * @param k 新 Key；传 {@link #KEEP_KEY} 表示保留原值，传空串表示清空。
     */
    public static synchronized boolean save(boolean en, String u, String k,
                                            String m, String dw, float ms) {
        return save(en, preEnabled, u, k, m, dw, ms);
    }

    /**
     * 保存全部设置。
     *
     * @param pre 事前分析开关；关闭时只做事后记录（事后再补算向量）。
     */
    public static synchronized boolean save(boolean en, boolean pre, String u, String k,
                                            String m, String dw, float ms) {
        return save(en, pre, u, k, m, dw, ms, minMargin);
    }

    /**
     * 保存全部设置（含领先间距）。
     *
     * @param mg 领先间距门槛；{@code <= 0} 表示沿用当前值
     */
    public static synchronized boolean save(boolean en, boolean pre, String u, String k,
                                            String m, String dw, float ms, float mg) {
        if (mg > 0f) {
            minMargin = mg;
        }
        // Key 单独落 secret.yaml；KEEP_KEY 表示不动原值。
        if (KEEP_KEY.equals(k)) {
            // 保持原样，不写 Key 文件
        } else {
            writeKey(k == null ? "" : k.trim());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 向量模型猫娘匹配：开启后，标点触发时会先把句子交给向量模型\n");
        sb.append("# 在颜文字语义向量索引里找最贴切的一条颜文字。\n");
        sb.append("vector: ").append(en ? "on" : "off").append('\n');
        sb.append("# 事前分析：off = 只做事后分析（等句子用完之后再记进记忆库）。\n");
        sb.append("# on = 打字打出标点时就先请求一次，命中立刻用；\n");
        sb.append("#      事后分析照常进行（事前包含事后）。\n");
        sb.append("pre: ").append(pre ? "on" : "off").append('\n');
        sb.append("# 接口地址（OpenAI 兼容）。填到 /v1 即可，会自动补 /embeddings\n");
        sb.append("url: \"").append(esc(u)).append("\"\n");
        sb.append("# 模型名\n");
        sb.append("model: \"").append(esc(m)).append("\"\n");
        sb.append("# 向量维度，auto = 按接口返回自动识别\n");
        sb.append("dim: ").append(dw == null || dw.trim().length() == 0
                ? "auto" : dw.trim()).append('\n');
        sb.append("# 相似度阈值 0~1（越大越严格）\n");
        sb.append("min: ").append(ms).append('\n');
        sb.append("# 领先间距：第一名要比第二名高出这么多才算「真的贴切」。\n");
        sb.append("margin: ").append(minMargin).append('\n');
        try {
            Cat.writeFile(CFG_FILE, sb.toString());
        } catch (Throwable t) {
            Cat.log("vector: 保存失败 " + t.getClass().getSimpleName());
            return false;
        }
        cfgStamp = -2;
        // 阈值/模型/开关变了，之前算出的决策与「没命中」标记都不再可信
        resetCache();
        load();
        return true;
    }

    /** 只清除 Key，其余设置保留。 */
    public static synchronized boolean clearKey() {
        writeKey("");
        Cat.log("vector: Key 已清除");
        return true;
    }

    /** YAML 双引号字符串里的转义。 */
    private static String esc(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 恢复默认（关闭），连同已保存的 Key 一起清除。 */
    public static synchronized void restoreDefaults() {
        Cat.writeFile(CFG_FILE, DEFAULT_CFG);
        cfgStamp = -2;
        writeKey("");
        load();
        decisions.clear();
        vecCache.clear();
        Cat.log("vector: 配置已恢复默认（Key 已清除）");
    }

    // ------------------------------------------------------------ 状态查询

    public static boolean enabled() {
        return enabled;
    }

    /**
     * 事前分析是否开启。关闭时只做事后分析：句子照常用完就记进记忆库，
     * 但打字过程中不会为了「立马挑一个颜文字」而发请求。
     */
    public static boolean preEnabled() {
        return preEnabled;
    }

    /** 开关打开且地址/模型都填了，才认为可用。 */
    public static boolean usable() {
        return enabled && url != null && url.length() > 0
                && model != null && model.length() > 0;
    }

    public static String url() {
        return url;
    }

    public static String model() {
        return model;
    }

    /** 是否已经配过 Key（界面只显示「已配置」，不显示内容）。 */
    public static boolean hasKey() {
        return key != null && key.length() > 0;
    }

    // 刻意不提供任何读回 Key 的方法：Key 是「只写不可读」的，
    // 界面与日志都只能知道「配没配」，拿不到内容本身。

    public static String dimText() {
        if (dim > 0) {
            return String.valueOf(dim);
        }
        return "auto";
    }

    public static float minScore() {
        return minScore;
    }

    /** 领先间距门槛。 */
    public static float minMargin() {
        return minMargin;
    }

    public static String lastStatus() {
        return lastStatus;
    }

    /** 地址里只显示主机名，避免日志里出现完整路径。 */
    private static String host(String u) {
        if (u == null || u.length() == 0) {
            return "";
        }
        try {
            return new URL(u).getHost();
        } catch (Throwable t) {
            return u.length() > 24 ? u.substring(0, 24) + "..." : u;
        }
    }

    // ------------------------------------------------------------ 决策缓存

    /** 句子 → 已确定的颜文字（本次会话内）。 */
    private static final Map<String, String> decisions =
            new ConcurrentHashMap<String, String>();

    /** 句子 → 已算好的向量，避免事后重复请求。 */
    private static final Map<String, float[]> vecCache =
            new ConcurrentHashMap<String, float[]>();

    /**
     * 本次会话里「问过向量但没命中」的句子。
     * 没有这个负缓存的话，同一句会在每个轮询周期重新入队，反复联网烧额度。
     * 换模型 / 重建索引 / 清空记忆时会一起清掉。
     */
    private static final Set<String> missed =
            java.util.Collections.synchronizedSet(new LinkedHashSet<String>());

    /** 负缓存上限：超过就先整体清空，避免常驻进程里无限增长。 */
    private static final int MISSED_MAX = 512;

    /** 正在排队或处理中的句子，避免重复入队。 */
    private static final Set<String> inflight =
            java.util.Collections.synchronizedSet(new LinkedHashSet<String>());

    /**
     * 只取「向量模型算出来的」结果（不含记忆库完全相同）。
     *
     * <p>检索链里记忆库之后的一级，见 {@link TagLib#pick(String)}。
     */
    public static String vectorDecision(String sentence) {
        if (sentence == null || sentence.length() == 0) {
            return null;
        }
        return decisions.get(sentence);
    }

    /**
     * 事前预取：把句子交给后台向量模型。
     *
     * <ul>
     *   <li>TXT 已完全相同 → 立即定案，不入队、不联网。</li>
     *   <li>未开启 / 不可用 → 什么都不做（随后走标签规则）。</li>
     *   <li>否则入队，后台算向量 + 找最相似。</li>
     * </ul>
     */
    public static void prefetch(String sentence) {
        fetch(sentence, false);
    }

    /**
     * 发送前兜底：语音输入与回车键发送不走「打字轮询」那条路，
     * 也就没有事前预取。这里主动问一次向量模型，命中就当场用它的结果。
     *
     * <p>与 {@link #prefetch} 的区别：<b>不受「事前分析」开关限制</b>。
     * 事前分析管的是「打字过程中要不要联网」，而这里已经是发送动作本身，
     * 属于用户明确要求的兜底路径。
     */
    public static void consult(String sentence) {
        fetch(sentence, true);
    }

    /**
     * 对一批句子做「发送前兜底」：先全部入队，再等一小会儿收结果。
     *
     * @param timeoutMs 最多等多久；超时就用规则结果，绝不卡死输入法
     * @return 是否全部就绪
     */
    public static boolean consultAll(List<String> sentences, long timeoutMs) {
        if (!enabled || sentences == null || sentences.isEmpty()) {
            return false;
        }
        for (String s : sentences) {
            consult(s);
        }
        return awaitAll(sentences, timeoutMs);
    }

    private static void fetch(String sentence, boolean force) {
        if (sentence == null || sentence.length() == 0) {
            return;
        }
        // 纯标点/纯空白残句（「。」「555。」）没内容可比，白烧额度
        if (!Cat.hasContent(sentence)) {
            return;
        }
        if (decisions.containsKey(sentence)) {
            return;
        }
        String e = VecStore.exact(sentence);
        if (e != null) {
            decisions.put(sentence, e);
            missed.remove(sentence);
            lastStatus = "完全相同命中：" + sentence;
            Cat.log("VEC 完全相同命中 [" + sentence + "] -> " + e);
            return;
        }
        // 这次会话里已经问过且没命中：同一句话别反复联网。
        // 换模型 / 重建索引 / 清空缓存时会清掉这个标记。
        if (missed.contains(sentence)) {
            return;
        }
        // 事前分析关掉时，打字过程中不发任何请求：
        // 句子照常会被 note() 记进记忆库（事后分析），只是不能「当场」用上。
        // force=true（发送前兜底）不受这条限制。
        if (!force && !preEnabled) {
            return;
        }
        if (!usable()) {
            return;
        }
        // 索引还没建：请求也没地方比，别浪费额度
        if (!KaoIndex.usable()) {
            return;
        }
        if (!inflight.add(sentence)) {
            return;
        }
        if (queue.size() >= QUEUE_MAX) {
            inflight.remove(sentence);
            return;
        }
        ensureWorker();
        queue.offer(sentence);
    }

    /** 这些句子是否都已有决策（不等待，只看当前状态）。 */
    public static boolean readyAll(List<String> sentences) {
        if (sentences == null || sentences.isEmpty()) {
            return true;
        }
        for (String s : sentences) {
            if (!decisions.containsKey(s)) {
                return false;
            }
        }
        return true;
    }

    /** 等到这些句子的决策都齐了，或超时。返回是否全部就绪。 */
    public static boolean awaitAll(List<String> sentences, long timeoutMs) {
        if (!usable() || sentences == null || sentences.isEmpty()) {
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allReady = true;
            for (String s : sentences) {
                if (!decisions.containsKey(s) && inflight.contains(s)) {
                    allReady = false;
                    break;
                }
            }
            if (allReady) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 事后记录：把「句子 → 实际使用的颜文字」写进 TXT。
     * 向量在后台补算；TXT 里已有完全相同的句子则跳过。
     */
    public static void note(final String sentence, final String kaomoji) {
        if (sentence == null || sentence.length() == 0
                || kaomoji == null || kaomoji.length() == 0) {
            return;
        }
        // 记忆库只存「句子=颜文字」，不需要向量、不需要联网，直接写。
        if (!enabled) {
            return;
        }
        if (VecStore.remember(sentence, kaomoji)) {
            Cat.log("MEM 已记录 [" + sentence + "] -> " + kaomoji);
        }
    }

    // ------------------------------------------------------------ 后台线程

    private static final LinkedBlockingQueue<String> queue =
            new LinkedBlockingQueue<String>();

    private static volatile boolean started = false;

    private static synchronized void ensureWorker() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    String job = null;
                    try {
                        job = queue.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        handle(job);
                    } catch (Throwable t) {
                        Cat.log("VEC 任务异常 " + t.getClass().getSimpleName());
                    }
                }
            }
        }, "qqcat-vector");
        t.setDaemon(true);
        t.start();
        Cat.log("vector: 后台线程已启动");
    }

    private static void handle(String job) {
        if (job == null) {
            return;
        }
        final String sentence = job;
        try {
            float[] v = embed(sentence);
            if (v == null) {
                lastStatus = "请求失败，回退关键词规则";
                return;
            }
            vecCache.put(sentence, v);
            KaoIndex.Entry near = KaoIndex.nearest(v);
            double score = KaoIndex.lastScore();
            double margin = KaoIndex.lastMargin();
            // 两个门槛：绝对分够高，且明显领先第二名。
            // 例外：如果前两名是同一个标签（都是同一类心情），
            // 挑哪个都不会跑偏，就不必再要求领先幅度。
            boolean decisive = margin >= minMargin;
            if (!decisive && near != null) {
                TagLib.load();
                decisive = TagLib.sameTag(near.kaomoji, KaoIndex.lastSecond());
            }
            if (near != null && score >= minScore && decisive) {
                decisions.put(sentence, near.kaomoji);
                lastStatus = String.format(java.util.Locale.US,
                        "向量命中 %.4f(+%.4f)：%s", score, margin, near.kaomoji);
                Cat.log("VEC 命中 [" + sentence + "] -> " + near.kaomoji
                        + " " + String.format(java.util.Locale.US, "%.4f", score)
                        + " 间距" + String.format(java.util.Locale.US, "%.4f", margin)
                        + (margin < minMargin ? " 同标签" : ""));
                // 按用户要求：事前命中同样把该句回写进记忆库
                if (VecStore.remember(sentence, near.kaomoji)) {
                    Cat.log("MEM 已回写 [" + sentence + "] -> " + near.kaomoji);
                }
            } else {
                // 记进负缓存：同一句这一轮会话不再重复问模型。
                // 输入法进程常驻，加个上限免得长年累积。
                if (missed.size() >= MISSED_MAX) {
                    missed.clear();
                }
                missed.add(sentence);
                lastStatus = near == null ? "索引为空，回退关键词规则"
                        : String.format(java.util.Locale.US,
                                "未达门槛（最近 %.4f 间距 %.4f %s）",
                                score, margin, near.kaomoji);
                Cat.log("VEC 未命中 [" + sentence + "] " + lastStatus);
                // 用户裁决（m04595）：只要这句真的送去向量算过，就把向量
                // 最接近的那条记下来 —— 即使没达门槛。写进去的值始终来自
                // 向量，不会是随机的；这样同样的话下次直接「完全相同」命中，
                // 结果稳定、也不再重复消耗额度。本次显示仍是回落结果。
                if (near != null && VecStore.remember(sentence, near.kaomoji)) {
                    Cat.log("MEM 已回写（未达门槛）[" + sentence + "] -> "
                            + near.kaomoji);
                }
            }
        } finally {
            inflight.remove(sentence);
        }
    }

    // ------------------------------------------------------------ 颜文字索引

    /** 重建进度描述，界面上显示用。 */
    private static volatile String rebuildStatus = "未重建过";

    public static String rebuildStatus() {
        return rebuildStatus;
    }

    /**
     * 索引是否需要重建。
     *
     * @return 不需要返回 null；需要则返回原因文案
     */
    public static String indexStale() {
        if (!enabled) {
            return null;
        }
        return KaoIndex.rebuildReason(model, Cat.kaomojiList());
    }

    /**
     * 重建颜文字语义向量索引。
     *
     * <p><b>只有用户在界面上点「重建向量」时才会走到这里</b>，
     * 模块任何自动流程都不会调用它（用户明确要求）。
     *
     * @param async true = 在后台线程跑（界面按钮用），false = 同步
     * @return 同步模式下返回结果描述；异步模式返回"已开始"
     */
    public static String rebuildIndex(boolean async) {
        if (!enabled) {
            return "请先开启向量模型";
        }
        if (!usable()) {
            return "请先填写接口地址和模型名";
        }
        if (async) {
            if (!rebuildRunning) {
                rebuildRunning = true;
                rebuildStatus = "正在重建…";
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            doRebuild();
                        } finally {
                            rebuildRunning = false;
                        }
                    }
                }, "qqcat-vec-build");
                t.setDaemon(true);
                t.start();
            }
            return "已开始重建";
        }
        return doRebuild();
    }

    private static volatile boolean rebuildRunning = false;

    public static boolean rebuilding() {
        return rebuildRunning;
    }

    /** 逐条算颜文字的语义向量 —— 一次批量 190 条，约 1~2 秒。 */
    private static String doRebuild() {
        List<String> kaos = new ArrayList<String>();
        List<String> descs = new ArrayList<String>();
        // 以「当前词库」为准：用户在「标签与词库」里改过的话，
        // 没描述的颜文字自动跳过，改过描述的下次重建就会更新。
        for (String[] pair : KaoIndex.pairs(Cat.kaomojiList())) {
            kaos.add(pair[0]);
            descs.add(pair[1]);
        }
        if (kaos.isEmpty()) {
            rebuildStatus = "没有可用的颜文字描述";
            return rebuildStatus;
        }
        long t0 = System.currentTimeMillis();
        rebuildStatus = "正在重建 " + kaos.size() + " 条…";
        Cat.log("VEC 开始重建索引 " + kaos.size() + " 条");
        List<float[]> vecs = embedBatch(descs);
        if (vecs == null) {
            rebuildStatus = "重建失败：" + lastStatus;
            Cat.log("VEC 重建失败 " + lastStatus);
            return rebuildStatus;
        }
        KaoIndex.save(kaos, vecs, model);
        // 索引换了，之前算出的决策和「没命中」标记都作废
        resetCache();
        long ms = System.currentTimeMillis() - t0;
        int ok = 0;
        for (float[] v : vecs) {
            if (v != null && v.length > 0) {
                ok++;
            }
        }
        rebuildStatus = "已重建 " + ok + "/" + kaos.size() + " 条，耗时 "
                + ms + "ms";
        lastStatus = rebuildStatus;
        Cat.log("VEC " + rebuildStatus);
        return rebuildStatus;
    }

    /**
     * 批量 embeddings。
     *
     * <p>服务端一次能收 190 条（实测），但保险起见按 48 条一批，
     * 返回的列表与输入一一对应，失败的槽位是 null。
     */
    private static List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<float[]>(texts.size());
        int step = 48;
        for (int from = 0; from < texts.size(); from += step) {
            int to = Math.min(texts.size(), from + step);
            List<float[]> part = embedSome(texts.subList(from, to));
            if (part == null) {
                return null;
            }
            out.addAll(part);
            rebuildStatus = "正在重建 " + to + "/" + texts.size() + "…";
        }
        return out;
    }

    /** 一批请求；返回的列表长度与输入一致（失败为 null）。 */
    private static List<float[]> embedSome(List<String> texts) {
        HttpURLConnection c = null;
        try {
            JSONArray arr = new JSONArray();
            for (String t : texts) {
                arr.put(t);
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", arr);

            URL u = new URL(endpoint());
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (key != null && key.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            byte[] payload = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable t) { }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                lastStatus = "HTTP " + code;
                Cat.log("VEC HTTP " + code + " " + clip(readAll(c.getErrorStream())));
                return null;
            }
            return parseList(readAll(c.getInputStream()), texts.size());
        } catch (Throwable t) {
            lastStatus = "异常 " + t.getClass().getSimpleName();
            Cat.log("VEC 批量请求异常 " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable t) { }
            }
        }
    }

    /** 解析批量返回；顺序与请求一致。 */
    static List<float[]> parseList(String resp, int want) {
        List<float[]> out = new ArrayList<float[]>(want);
        for (int i = 0; i < want; i++) {
            out.add(null);
        }
        if (resp == null) {
            return out;
        }
        try {
            JSONObject o = new JSONObject(resp);
            Object data = o.opt("data");
            if (!(data instanceof JSONArray)) {
                return out;
            }
            JSONArray arr = (JSONArray) data;
            for (int i = 0; i < arr.length(); i++) {
                Object it = arr.opt(i);
                JSONArray emb = null;
                int idx = i;
                if (it instanceof JSONObject) {
                    emb = ((JSONObject) it).optJSONArray("embedding");
                    idx = ((JSONObject) it).optInt("index", i);
                } else if (it instanceof JSONArray) {
                    emb = (JSONArray) it;
                }
                if (emb == null || emb.length() == 0) {
                    continue;
                }
                if (idx < 0 || idx >= want) {
                    idx = i;
                    if (idx >= want) {
                        continue;
                    }
                }
                float[] v = new float[emb.length()];
                for (int j = 0; j < emb.length(); j++) {
                    v[j] = (float) emb.optDouble(j, 0d);
                }
                out.set(idx, v);
                if (dim != v.length) {
                    dim = v.length;
                    Cat.log("VEC 识别到维度 " + dim);
                }
            }
        } catch (Throwable t) {
            // 返回空列表
        }
        return out;
    }

    // ------------------------------------------------------------ HTTP

    /**
     * 补全成真正的 embeddings 接口地址。
     *
     * <p>用户可能只填到 base（`https://api.siliconflow.cn/v1`），
     * 也可能直接填完整路径。两种都接受：
     * <ul>
     *   <li>以 `/embeddings` 结尾 → 原样使用</li>
     *   <li>以 `/v1` 或 `/v1/` 结尾 → 补 `/embeddings`</li>
     *   <li>其它 → 补 `/v1/embeddings`</li>
     * </ul>
     */
    public static String endpoint() {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.length() == 0) {
            return "";
        }
        if (u.endsWith("/embeddings")) {
            return u;
        }
        if (u.endsWith("/v1") || u.endsWith("/v1beta")) {
            return u + "/embeddings";
        }
        return u + "/v1/embeddings";
    }

    /**
     * 调一次 embeddings 接口。
     *
     * <p>兼容两种返回：
     * <pre>
     *   {"data":[{"embedding":[...]}]}      OpenAI 风格
     *   {"embedding":[...]}                 简化风格
     * </pre>
     *
     * @return 向量；任何失败都返回 null（调用方回退标签规则）
     */
    public static float[] embed(String text) {
        if (!usable() || text == null || text.length() == 0) {
            return null;
        }
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("input", text);

            URL u = new URL(endpoint());
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (key != null && key.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            byte[] payload = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable t) { }
            }

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(c.getErrorStream());
                lastStatus = "HTTP " + code;
                Cat.log("VEC HTTP " + code + " " + clip(err));
                return null;
            }
            String resp = readAll(c.getInputStream());
            float[] v = parse(resp);
            if (v == null) {
                lastStatus = "返回无法解析";
                Cat.log("VEC 解析失败 " + clip(resp));
                return null;
            }
            if (dim != v.length) {
                dim = v.length;
                Cat.log("VEC 识别到维度 " + dim);
            }
            return v;
        } catch (Throwable t) {
            lastStatus = "异常 " + t.getClass().getSimpleName();
            Cat.log("VEC 请求异常 " + t.getClass().getSimpleName() + " "
                    + clip(String.valueOf(t.getMessage())));
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable t) { }
            }
        }
    }

    /** 解析响应体里的 embedding 数组。 */
    static float[] parse(String resp) {
        if (resp == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(resp);
            JSONArray arr = null;
            Object data = o.opt("data");
            if (data instanceof JSONArray && ((JSONArray) data).length() > 0) {
                Object first = ((JSONArray) data).opt(0);
                if (first instanceof JSONObject) {
                    arr = ((JSONObject) first).optJSONArray("embedding");
                } else if (first instanceof JSONArray) {
                    arr = (JSONArray) first;
                }
            }
            if (arr == null) {
                arr = o.optJSONArray("embedding");
            }
            if (arr == null || arr.length() == 0) {
                return null;
            }
            float[] v = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                v[i] = (float) arr.optDouble(i, 0d);
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 界面上的「测试连接」按钮：同步跑一次，返回结果描述。 */
    public static String test(String sample) {
        if (!enabled) {
            return "未开启";
        }
        if (!usable()) {
            return "请先填写接口地址和模型名";
        }
        long t0 = System.currentTimeMillis();
        float[] v = embed(sample == null || sample.length() == 0 ? "你好" : sample);
        long ms = System.currentTimeMillis() - t0;
        if (v == null) {
            return "失败：" + lastStatus + "（" + ms + "ms）";
        }
        lastStatus = "正常，维度 " + v.length;
        return "成功：维度 " + v.length + "，耗时 " + ms + "ms";
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable t) { }
        }
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String r = s.replace('\n', ' ').replace('\r', ' ');
        return r.length() > 200 ? r.substring(0, 200) + "..." : r;
    }

    /** 清空决策缓存（界面「清空记忆」后调用）。 */
    public static void resetCache() {
        decisions.clear();
        vecCache.clear();
        missed.clear();
    }

    /** 供界面显示：已缓存多少条决策。 */
    public static int decisionCount() {
        return decisions.size();
    }
}
