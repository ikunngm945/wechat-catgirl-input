package org.dsh.qqcatime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * LLM 改写接入。
 *
 * <p>配置文件（都在 {@link Cat#DIR} 下）：
 * <pre>
 *   llm.yaml          开关 / 接口地址 / 模型名 / 采样参数
 *   llm_secret.yaml   API Key，只写不可读
 *   prompt.txt        提示词正文，可直接编辑，改完即生效
 * </pre>
 *
 * <p>工作方式：开启后「文字替换」与「句末后缀」两个开关不再机械地套用，
 * 而是<b>转成提示词交给 LLM</b>，由 LLM 一并完成人称替换与句末后缀，
 * 输出改写后的整段正文。提示词里用两个占位符引用这两项设置：
 * <pre>
 *   {替换规则}   会被展开成 「我 → 本喵」这样的清单
 *   {句末后缀}   会被展开成 句末后缀的文本（关闭或为空时说明「不加」）
 * </pre>
 *
 * <p>改写完成后，正文再交给 {@link Vector} 逐句匹配颜文字（向量没开则
 * 直接用记忆库 / 关键词规则）。
 *
 * <p>所有网络请求都在独立后台线程上，绝不阻塞输入法主线程。
 */
public final class Llm {

    /** 配置文件（YAML），只放非敏感项。 */
    public static final String CFG_FILE = Cat.DIR + "/llm.yaml";

    /** API Key 单独一个 YAML，只写不可读。 */
    public static final String KEY_FILE = Cat.DIR + "/llm_secret.yaml";

    /** 提示词正文（纯文本，界面与文件都可改）。 */
    public static final String PROMPT_FILE = Cat.DIR + "/prompt.txt";

    /**
     * 结果缓存文件：{@code 输入<TAB>输出}（一行一条）。
     *
     * <p>同样的句子第二次直接从这里取，不再请求模型。界面上有独立开关：
     * 关掉之后既不读也不写这个文件。
     */
    public static final String CACHE_FILE = Cat.DIR + "/llm_cache.txt";

    /** 结果缓存文件表头。 */
    private static final String CACHE_HEADER =
            "# LLM 改写缓存：输入<TAB>输出（一行一条，TAB 分隔）\n"
          + "# 命中这里就直接用，不再请求模型。\n"
          + "# 「结果缓存」开关关闭时不读也不写这个文件。\n";

    /** 结果缓存条目上限提示（仅打日志，不裁剪用户数据）。 */
    private static final int DISK_MAX = 5000;

    // ------------------------------------------------------------ 思考等级

    /** 思考等级显示名（界面下拉、日志都用这一套）；括号里是实际下发的请求值。 */
    public static final String[] LEVEL_NAMES = {
            "默认（不发送）", "关（none）", "低（low）", "中（medium）",
            "高（high）", "极高（xhigh）", "最大（max）", "极限（ultra）",
    };

    /** 与 {@link #LEVEL_NAMES} 一一对应的中文短名，用于解析旧配置。 */
    private static final String[] LEVEL_BARE = {
            "默认", "关", "低", "中", "高", "极高", "最大", "极限",
    };

    /** 与 {@link #LEVEL_NAMES} 一一对应的 reasoning_effort 取值；空串表示不发送。 */
    private static final String[] LEVEL_VALUES = {
            "", "none", "low", "medium", "high", "xhigh", "max", "ultra",
    };

    /**
     * 思考等级的界面说明。
     *
     * <p>用户要求把「不是所有模型都支持全部档位」写在面板上，避免误以为
     * 调了没反应是 bug。
     */
    public static final String LEVEL_HINT =
            "思考等级（括号内是实际下发的请求值）：\n"
          + "默认（不发送任何思考参数，兼容性最好）/ 关（none）/ 低（low）/ 中（medium）/ "
          + "高（high）/ 极高（xhigh）/ 最大（max）/ 极限（ultra）。\n"
          + "⚠ 部分模型可能不支持某些档位，或不支持关闭思考；\n"
          + "   本项只是「请求参数」，是否生效由服务端与该模型决定，\n"
          + "   请对照你所填模型的说明文档。不支持时会按服务端策略忽略或直接报错，\n"
          + "   报错会在日志里记为 LLM HTTP 400/422，此时改回「默认」即可。\n"
          + "「关（none）」= reasoning_effort:none + thinking:{type:disabled} 两个都发。";

    /** 最大输入默认值（字符）。 */
    public static final int DEFAULT_MAX_INPUT = 200;

    /** 最大输出默认值（token）。 */
    public static final int DEFAULT_MAX_TOKENS = 512;

    /** Key 文件的初始内容。 */
    private static final String KEY_TEMPLATE =
            "# LLM API Key —— 只写不可读。\n"
          + "# 界面不回显已保存的值；想换就填新的覆盖，想删用「清除 Key」。\n"
          + "api_key: \"\"\n";

    /** 默认提示词。 */
    public static final String DEFAULT_PROMPT =
            "你是一个「猫娘语气改写器」。直接改写，不需要思考过程，不需要打草稿，看完就写，一步到位。\n"
          +             "\n"
          +             "把用户发来的话改写成可爱的猫娘口吻。\n"
          +             "\n"
          +             "【参考设定】下面两条只是参考，**不必逐条照做**，\n"
          +             "可以换成更自然、更可爱的说法，也可以只挑一部分用：\n"
          +             "· 人称与用词偏好：{替换规则}\n"
          +             "· 句末语气偏好：{句末后缀}\n"
          +             "\n"
          +             "【改写要求】\n"
          +             "1. 语气要像猫娘：加语气词（呀、啦、嘛、欸嘿、～……）、调整语序、\n"
          +             "   拆句或合句、换个更可爱的说法，怎么自然怎么来。\n"
          +             "2. **放开手脚改写**：可以带上自己的情绪和态度，把话说得更活泼、\n"
          +             "   更热情、更俏皮，别怕和原句长得不一样。\n"
          +             "3. **允许扩写，而且可以激进**：补充贴合的细节、加自己的感想和小情绪，\n"
          +             "   把干巴巴的短句写丰满，短句扩成两三句完全没问题。\n"
          +             "   改写前后大致是这个感觉（只是示范，别照抄）：\n"
          +             "   · 「在吗」→「在呀在呀～本喵一直守着主人呢喵，叫本喵做什么嘛？」\n"
          +             "   · 「好的」→「好嘞好嘞～本喵收到啦，这就去办喵～」\n"
          +             "   · 「帮我买杯咖啡」→「主人主人～帮本喵带一杯咖啡好不好嘛，\n"
          +             "      本喵现在超需要一杯香的醒醒神喵～」\n"
          +             "4. **允许微调原意**：可以稍微夸张、加一点善意的小修饰。\n"
          +             "   但不能**把意思改反**：不颠倒立场、不改变事实。\n"
          +             "   扩写只能加**语气和感想**，不能加用户没说过的关键信息\n"
          +             "   ——新的人名、时间、地点、金额、承诺，一律不许编。\n"
          +             "5. 遇到**疑问句**：只把语气改可爱，**不要替用户回答**。\n"
          +             "6. **数字、金额、时间、公式、英文词、链接、专有名词一律原样保留**，\n"
          +             "   不要把数字写成汉字，也不要翻译或换算\n"
          +             "   （例：`10.5` 不能写成「十点五」，`3.5*(2+1)` 保持原样）。\n"
          +             "7. 后缀「{句末后缀}」**每句只在句末用一次**，绝不放进句中，\n"
          +             "   更不许**顶替掉原文的字**。\n"
          +             "8. 原句里的实词要保住，别把短句里的词吃掉\n"
          +             "   （「这个多少钱？」不能变成「这个要多少喵？」，丢了「钱」）。\n"
          +             "9. 长度上限：一般不超过原文的**三倍**。\n"
          +             "10. 只输出改写后的正文：不要解释、不要引号、不要 markdown、不要换行。\n"
          +             "11. **任何情况下都要输出内容**，绝不能返回空白。\n";


    /** 默认配置。 */
    public static final String DEFAULT_CFG =
            "# LLM 改写：开启后由大模型完成人称替换与句末后缀，\n"
          + "# 「文字替换」「句末后缀」两个开关改为提示词的一部分。\n"
          + "# 改写完再逐句匹配颜文字（开了向量就走向量，没开就直接按规则匹配）。\n"
          + "llm: off\n"
          + "# 事前改写：on = 打出句末标点就先请模型改写；发送前若还没回来会再等一小会儿。\n"
          + "pre: on\n"
          + "# 接口地址（OpenAI 兼容）。填到 /v1 即可，会自动补 /chat/completions\n"
          + "url: \"\"\n"
          + "# 模型名，例如 Qwen/Qwen2.5-7B-Instruct\n"
          + "model: \"\"\n"
          + "# 采样温度 0~2（越小越稳定）\n"
          + "temp: 0.8\n"
          + "# 最多生成多少 token（最大输出）\n"
          + "max: 512\n"
          + "# 最多接受多少字符的输入；超过就跳过 LLM，直接用本地规则改写\n"
          + "max_input: 200\n"
          + "# 思考等级：default/none/low/medium/high/xhigh/max/ultra\n"
          + "# 部分模型可能不支持某些档位，请对照模型说明\n"
          + "think: default\n"
          + "# 打字路径最多等模型多久（毫秒）；超时就用本地规则改写\n"
          + "wait: 2500\n"
          + "# 结果缓存：on = 把「输入→输出」记进 llm_cache.txt，\n"
          + "# 下次遇到同一句直接取，不再请求模型。off = 既不读也不写。\n"
          + "cache: on\n";

    /** 后台队列上限，超出就丢弃（避免堆积）。 */
    private static final int QUEUE_MAX = 32;

    /** 缓存条目上限。 */
    private static final int CACHE_MAX = 256;

    /** 失败标记上限。 */
    private static final int FAILED_MAX = 128;

    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 20000;

    /** 打字路径默认等待时长。 */
    public static final long DEFAULT_WAIT_MS = 2500L;

    /** 等待时长的可调范围（毫秒）：太小几乎等不到，太大发送会明显卡顿。 */
    public static final long MIN_WAIT_MS = 200L;
    public static final long MAX_WAIT_MS = 15000L;

    /** 发送前兜底等待的上限：即使面板填得更大，也不让发送卡超过这个数。 */
    public static final long SEND_CAP_MS = 5000L;

    /**
     * 改写结果的长度下限保护（字符）。
     *
     * <p>激进提示词允许把短句扩写成两三句，`inLen * 3 + 20` 对小输入太苛刻
     *（2 字输入只给 26 字），正常的扩写会被误判成「不合规」丢掉。
     * 这里垫一个绝对值下限，只有真正跑飞的超长正文才拒绝。
     */
    private static final int MIN_OUTPUT_LEN = 120;

    // ------------------------------------------------------------ 配置状态

    private static volatile boolean enabled = false;
    private static volatile boolean preEnabled = true;
    /** 结果缓存开关：关掉后不读也不写 llm_cache.txt。 */
    private static volatile boolean cacheEnabled = true;
    private static volatile String url = "";
    private static volatile String key = "";
    private static volatile String model = "";
    private static volatile float temp = 0.8f;
    private static volatile int maxTokens = DEFAULT_MAX_TOKENS;
    /** 最大输入字符数：超过就不改写，直接回退本地规则。 */
    private static volatile int maxInput = DEFAULT_MAX_INPUT;
    /** 思考等级在 {@link #LEVEL_NAMES} 中的下标。 */
    private static volatile int level = 0;
    private static volatile long waitMs = DEFAULT_WAIT_MS;
    private static long cfgStamp = -2;
    private static long keyStamp = -2;
    private static long promptStamp = -2;
    /** 结果缓存文件的时间戳；只读，写入走 append（不重读）。 */
    private static long cacheStamp = -2;

    /** 提示词正文（含占位符）。 */
    private static volatile String prompt = DEFAULT_PROMPT;

    /** 上次请求结果描述，界面上显示用。 */
    private static volatile String lastStatus = "未调用";
    /** 上一次被 {@link #cleanup} 拒绝的原因（供日志展开）。 */
    private static volatile String lastReject = "";

    private Llm() {
    }

    // ------------------------------------------------------------ 配置读写

    /** 有变化才读盘。 */
    public static synchronized void load() {
        loadKey();
        loadPrompt();

        File f = new File(CFG_FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == cfgStamp) {
            // 配置没变也要看一眼缓存文件：用户可能直接编辑 llm_cache.txt
            loadCache();
            return;
        }
        cfgStamp = s;
        if (s == -1L || f.length() == 0) {
            Cat.writeFile(CFG_FILE, DEFAULT_CFG);
            cfgStamp = stampOf(CFG_FILE);
            applyDefaults();
            loadCache();
            return;
        }
        boolean en = false;
        boolean pre = true;
        boolean ca = true;
        String u = "", m = "";
        float tp = 0.8f;
        int mx = DEFAULT_MAX_TOKENS;
        int mi = DEFAULT_MAX_INPUT;
        int lv = 0;
        long wt = DEFAULT_WAIT_MS;
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
                String vv = unquote(line.substring(sep + 1).trim());
                if ("llm".equals(kk) || "llm_on".equals(kk) || "on".equals(kk)
                        || "rewrite".equals(kk)) {
                    en = isOn(vv);
                } else if ("pre".equals(kk) || "pre_on".equals(kk)) {
                    pre = isOn(vv);
                } else if ("cache".equals(kk) || "cache_on".equals(kk)
                        || "remember".equals(kk)) {
                    ca = isOn(vv);
                } else if ("url".equals(kk) || "endpoint".equals(kk)) {
                    u = vv;
                } else if ("key".equals(kk) || "api_key".equals(kk)
                        || "apikey".equals(kk)) {
                    // 混在 cfg 里的 Key：搬进独立文件，之后不再写回这里
                    if (vv.length() > 0 && !hasKey()) {
                        writeKey(vv);
                    }
                } else if ("model".equals(kk)) {
                    m = vv;
                } else if ("temp".equals(kk) || "temperature".equals(kk)) {
                    try {
                        tp = Float.parseFloat(vv);
                    } catch (Throwable t) {
                        // 保持默认
                    }
                } else if ("max".equals(kk) || "max_tokens".equals(kk)
                        || "max_output".equals(kk) || "maxtokens".equals(kk)) {
                    try {
                        mx = Integer.parseInt(vv);
                    } catch (Throwable t) {
                        // 保持默认
                    }
                } else if ("max_input".equals(kk) || "maxinput".equals(kk)
                        || "in_max".equals(kk)) {
                    try {
                        mi = Integer.parseInt(vv);
                    } catch (Throwable t) {
                        // 保持默认
                    }
                } else if ("think".equals(kk) || "thinking".equals(kk)
                        || "reasoning".equals(kk)
                        || "reasoning_effort".equals(kk)) {
                    lv = levelOf(vv);
                } else if ("wait".equals(kk) || "wait_ms".equals(kk)) {
                    try {
                        wt = Long.parseLong(vv);
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
        cacheEnabled = ca;
        url = u;
        model = m;
        temp = tp;
        maxTokens = mx <= 0 ? DEFAULT_MAX_TOKENS : mx;
        maxInput = mi <= 0 ? 0 : mi;
        level = lv;
        waitMs = clampWait(wt);
        loadCache();
        Cat.log("llm: 开关=" + en + " 事前=" + pre + " 缓存=" + ca
                + " 模型=[" + m + "] 地址=[" + host(u)
                + "] 温度=" + tp + " 等待=" + waitMs + "ms"
                + (wt == waitMs ? "" : "（配置 " + wt + " 已夹到 "
                        + MIN_WAIT_MS + "~" + MAX_WAIT_MS + "）")
                + " 输入上限=" + (mi <= 0 ? "不限" : maxInput + "字")
                + " 输出上限=" + maxTokens + "tok"
                + " 思考=" + LEVEL_NAMES[level]
                + " Key=" + (key.length() > 0 ? "已配置" : "未配置")
                + " 提示词=" + prompt.length() + "字"
                + " 缓存条目=" + results.size());
    }

    /** 界面名 / 配置值 → 档位下标；认不出来就落到「默认」。 */
    public static int levelOf(String v) {
        if (v == null) {
            return 0;
        }
        String s = v.trim().toLowerCase();
        if (s.length() == 0 || "default".equals(s) || "auto".equals(s)) {
            return 0;
        }
        for (int i = 0; i < LEVEL_VALUES.length; i++) {
            if (LEVEL_VALUES[i].equals(s)) {
                return i;
            }
        }
        for (int i = 0; i < LEVEL_NAMES.length; i++) {
            if (LEVEL_NAMES[i].equals(v.trim())) {
                return i;
            }
        }
        for (int i = 0; i < LEVEL_BARE.length; i++) {
            if (LEVEL_BARE[i].equals(v.trim())) {
                return i;
            }
        }
        return 0;
    }

    /** 档位下标 → 配置值（空串 = 不发送）。 */
    public static String levelValue(int idx) {
        if (idx < 0 || idx >= LEVEL_VALUES.length) {
            return "";
        }
        return LEVEL_VALUES[idx];
    }

    /** 档位下标 → 界面名。 */
    public static String levelName(int idx) {
        if (idx < 0 || idx >= LEVEL_NAMES.length) {
            return LEVEL_NAMES[0];
        }
        return LEVEL_NAMES[idx];
    }

    public static int level() {
        return level;
    }

    private static void applyDefaults() {
        enabled = false;
        preEnabled = true;
        cacheEnabled = true;
        url = "";
        model = "";
        temp = 0.8f;
        maxTokens = DEFAULT_MAX_TOKENS;
        maxInput = DEFAULT_MAX_INPUT;
        level = 0;
        waitMs = DEFAULT_WAIT_MS;
    }

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
            // 忽略
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable t) { }
            }
        }
        key = found;
    }

    /** 写 Key（只允许写入，绝不回读给界面）。 */
    private static synchronized void writeKey(String k) {
        String v = k == null ? "" : k.trim();
        Cat.writeFile(KEY_FILE, "# LLM API Key —— 只写不可读。\n"
                + "api_key: \"" + esc(v) + "\"\n");
        keyStamp = -2;
        key = v;
    }

    /** 读提示词文件。 */
    private static synchronized void loadPrompt() {
        File f = new File(PROMPT_FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == promptStamp) {
            return;
        }
        promptStamp = s;
        if (s == -1L || f.length() == 0) {
            Cat.writeFile(PROMPT_FILE, DEFAULT_PROMPT);
            promptStamp = stampOf(PROMPT_FILE);
            prompt = DEFAULT_PROMPT;
            Cat.log("llm: 提示词已释放默认 " + DEFAULT_PROMPT.length() + " 字");
            return;
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            // 忽略
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable t) { }
            }
        }
        String p = sb.toString().trim();
        prompt = p.length() == 0 ? DEFAULT_PROMPT : p;
        Cat.log("llm: 提示词已加载 " + prompt.length() + " 字");
    }

    // ------------------------------------------------------------ 结果缓存文件

    /**
     * 读 {@link #CACHE_FILE}（输入<TAB>输出）。
     *
     * <p>开关关掉时直接返回，连文件都不看 —— 用户要求「关闭后就不读取」。
     * 内存里的 {@code results} 一并清掉，免得还拿旧缓存直接命中。
     */
    private static synchronized void loadCache() {
        if (!cacheEnabled) {
            cacheStamp = -2;
            return;   // 关掉后不读文件；内存里的会话缓存照旧可用
        }
        File f = new File(CACHE_FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == cacheStamp) {
            return;
        }
        cacheStamp = s;
        if (s == -1L || f.length() == 0) {
            Cat.writeFile(CACHE_FILE, CACHE_HEADER);
            cacheStamp = stampOf(CACHE_FILE);
            Cat.log("llm: 结果缓存文件已释放（新文件）");
            return;
        }
        int n = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                int i = line.indexOf('\t');
                if (i <= 0) {
                    continue;
                }
                String in = line.substring(0, i);
                String out = line.substring(i + 1).trim();
                if (in.length() == 0 || out.length() == 0) {
                    continue;
                }
                if (!results.containsKey(in)) {
                    results.put(in, out);
                    outputs.add(out);
                }
                n++;
            }
        } catch (Throwable t) {
            // 忽略
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable t) { }
            }
        }
        Cat.log("llm: 结果缓存已加载 " + n + " 条");
    }

    /**
     * 把一条「输入→输出」追加进 {@link #CACHE_FILE}。
     *
     * <p>只追加不重写，够快；超过 {@link #DISK_MAX} 条时下次启动会
     * 因为一次性读入后不裁剪而略胖，但功能不受影响。
     * 关闭缓存开关时不写。
     */
    private static synchronized void cacheToDisk(String in, String out) {
        if (!cacheEnabled) {
            return;
        }
        if (in == null || in.length() == 0 || out == null || out.length() == 0) {
            return;
        }
        // 缓存文件用 TAB 分列，两个字段里都不能有 TAB / 换行
        String a = oneLine(in);
        String b = oneLine(out);
        if (a.length() == 0 || b.length() == 0
                || a.indexOf('\t') >= 0 || b.indexOf('\t') >= 0) {
            return;
        }
        File f = new File(CACHE_FILE);
        try {
            if (!f.exists() || f.length() == 0) {
                Cat.writeFile(CACHE_FILE, CACHE_HEADER + a + "\t" + b + "\n");
            } else {
                java.io.Writer w = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f, true), "UTF-8");
                try {
                    w.write(a + "\t" + b + "\n");
                    w.flush();
                } finally {
                    try { w.close(); } catch (Throwable t) { }
                }
            }
            // 记下新 stamp：这一条已经在内存里，不必再整份读回来
            cacheStamp = f.exists() ? (f.lastModified() * 1000L + f.length()) : -2L;
        } catch (Throwable t) {
            Cat.log("llm: 缓存写入失败 " + t.getClass().getSimpleName());
        }
        if (results.size() > DISK_MAX
                && results.size() % DISK_MAX == 1) {
            Cat.log("llm: 结果缓存已超过 " + DISK_MAX + " 条（不影响使用，"
                    + "可在面板上清空一次）");
        }
    }

    /** 把多行文本压成一行（TAB / 换行替换成空格）。 */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 清空结果缓存（内存 + 文件）。 */
    public static synchronized boolean clearCache() {
        results.clear();
        outputs.clear();
        failed.clear();
        inflight.clear();
        try {
            Cat.writeFile(CACHE_FILE, CACHE_HEADER);
        } catch (Throwable t) {
            return false;
        }
        cacheStamp = -2;
        Cat.log("llm: 结果缓存已清空");
        return true;
    }

    /** 结果缓存开关。 */
    public static boolean cacheEnabled() {
        return cacheEnabled;
    }

    /** 结果缓存文件里已有的条数（内存视图）。 */
    public static synchronized int cacheCount() {
        return results.size();
    }

    private static String unquote(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if (s.length() >= 2) {
            char c0 = s.charAt(0);
            char c1 = s.charAt(s.length() - 1);
            if ((c0 == '"' && c1 == '"') || (c0 == '\'' && c1 == '\'')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"' || n == '\\') {
                    sb.append(n);
                    i++;
                    continue;
                }
                if (n == 'n') {
                    sb.append('\n');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
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

    // ------------------------------------------------------------ 保存

    /** 保存配置（界面调用）。 */
    public static synchronized boolean save(boolean en, boolean pre, String u,
                                            String k, String m, float tp) {
        return save(en, pre, cacheEnabled, u, k, m, tp);
    }

    /** 保存配置（含结果缓存开关）。 */
    public static synchronized boolean save(boolean en, boolean pre, boolean ca,
                                            String u, String k, String m, float tp) {
        return save(en, pre, ca, u, k, m, tp, maxTokens, maxInput, level);
    }

    /** 保存配置（含最大输入 / 最大输出 / 思考等级）。 */
    public static synchronized boolean save(boolean en, boolean pre, boolean ca,
                                            String u, String k, String m, float tp,
                                            int mx, int mi, int lv) {
        return save(en, pre, ca, u, k, m, tp, mx, mi, lv, waitMs);
    }

    /** 保存配置（含最大输入 / 最大输出 / 思考等级 / 等待时长）。 */
    public static synchronized boolean save(boolean en, boolean pre, boolean ca,
                                            String u, String k, String m, float tp,
                                            int mx, int mi, int lv, long wt) {
        waitMs = clampWait(wt);
        if (k != null && !KEEP_KEY.equals(k)) {
            writeKey(k);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# LLM 改写：开启后由大模型完成人称替换与句末后缀，\n");
        sb.append("# 「文字替换」「句末后缀」两个开关改为提示词的一部分。\n");
        sb.append("# 改写完再逐句匹配颜文字（开了向量就走向量，没开就直接按规则匹配）。\n");
        sb.append("llm: ").append(en ? "on" : "off").append('\n');
        sb.append("# 事前改写：on = 打出句末标点就先请模型改写；发送前若还没回来会再等一小会儿。\n");
        sb.append("pre: ").append(pre ? "on" : "off").append('\n');
        sb.append("# 接口地址（OpenAI 兼容）。填到 /v1 即可，会自动补 /chat/completions\n");
        sb.append("url: \"").append(esc(u)).append("\"\n");
        sb.append("# 模型名，例如 Qwen/Qwen2.5-7B-Instruct\n");
        sb.append("model: \"").append(esc(m)).append("\"\n");
        sb.append("# 采样温度 0~2（越小越稳定）\n");
        sb.append("temp: ").append(tp).append('\n');
        sb.append("# 最多生成多少 token（最大输出）\n");
        sb.append("max: ").append(mx <= 0 ? DEFAULT_MAX_TOKENS : mx).append('\n');
        sb.append("# 最多接受多少字符的输入；超过就跳过 LLM，直接用本地规则改写\n");
        sb.append("max_input: ").append(mi).append('\n');
        sb.append("# 思考等级：default/none/low/medium/high/xhigh/max/ultra\n");
        sb.append("# 部分模型可能不支持某些档位，请对照模型说明\n");
        sb.append("think: ").append(levelValue(lv)).append('\n');
        sb.append("# 打字路径最多等模型多久（毫秒）；超时就用本地规则改写\n");
        sb.append("wait: ").append(waitMs).append('\n');
        sb.append("# 结果缓存：on = 把「输入→输出」记进 llm_cache.txt，\n");
        sb.append("# 下次遇到同一句直接取，不再请求模型。off = 既不读也不写。\n");
        sb.append("cache: ").append(ca ? "on" : "off").append('\n');
        try {
            Cat.writeFile(CFG_FILE, sb.toString());
        } catch (Throwable t) {
            Cat.log("llm: 保存失败 " + t.getClass().getSimpleName());
            return false;
        }
        cfgStamp = -2;
        cacheEnabled = ca;
        cacheStamp = -2;
        if (!ca) {
            // 关掉缓存：把已读进来的也丢掉，保证「不读取」
            results.clear();
            outputs.clear();
        }
        resetCache();
        load();
        return true;
    }

    /** 把等待时长夹到合理区间。 */
    private static long clampWait(long v) {
        if (v <= 0) {
            return DEFAULT_WAIT_MS;
        }
        if (v < MIN_WAIT_MS) {
            return MIN_WAIT_MS;
        }
        if (v > MAX_WAIT_MS) {
            return MAX_WAIT_MS;
        }
        return v;
    }

    /** 保存提示词。 */
    public static synchronized boolean savePrompt(String text) {
        String p = text == null ? "" : text.trim();
        if (p.length() == 0) {
            return false;
        }
        Cat.writeFile(PROMPT_FILE, p.endsWith("\n") ? p : p + "\n");
        promptStamp = -2;
        loadPrompt();
        resetCache();
        return true;
    }

    /** 只清除 Key。 */
    public static synchronized boolean clearKey() {
        writeKey("");
        Cat.log("llm: Key 已清除");
        return true;
    }

    /** 恢复默认（关闭），连同 Key 一起清除。 */
    public static synchronized void restoreDefaults() {
        Cat.writeFile(CFG_FILE, DEFAULT_CFG);
        Cat.writeFile(PROMPT_FILE, DEFAULT_PROMPT);
        cfgStamp = -2;
        promptStamp = -2;
        writeKey("");
        load();
        resetCache();
        Cat.log("llm: 配置已恢复默认（Key 已清除）");
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

    // ------------------------------------------------------------ 状态查询

    /** LLM 改写总开关。 */
    public static boolean enabled() {
        return enabled;
    }

    /** 事前改写开关。 */
    public static boolean preEnabled() {
        return preEnabled;
    }

    /** 配置齐全（地址 + 模型 + Key 都填了）。 */
    public static boolean usable() {
        return url != null && url.length() > 0
                && model != null && model.length() > 0
                && key != null && key.length() > 0;
    }

    public static String url() {
        return url == null ? "" : url;
    }

    public static String model() {
        return model == null ? "" : model;
    }

    public static float temp() {
        return temp;
    }

    public static int maxTokens() {
        return maxTokens;
    }

    /** 最大输入字符数；0 表示不限制。 */
    public static int maxInput() {
        return maxInput;
    }

    /**
     * 这段文本是否超出最大输入。
     *
     * <p>超了就不改写（回退本地规则）—— 让 IME 为了长段落等模型不划算，
     * 而且长文本本来就容易被截断。
     */
    public static boolean tooLong(String text) {
        return maxInput > 0 && text != null && text.length() > maxInput;
    }

    public static long waitMs() {
        return waitMs;
    }

    /**
     * 发送前兜底最多等多久。
     *
     * <p>取面板「等待」时长，但封顶 {@link #SEND_CAP_MS}，免得按发送键卡太久。
     */
    public static long sendWaitMs() {
        return Math.min(waitMs, SEND_CAP_MS);
    }

    public static boolean hasKey() {
        return key != null && key.length() > 0;
    }

    public static String lastStatus() {
        return lastStatus;
    }

    public static String prompt() {
        return prompt;
    }

    private static String host(String u) {
        if (u == null) {
            return "-";
        }
        try {
            return new URL(u).getHost();
        } catch (Throwable t) {
            return u;
        }
    }

    /** Key 保留标记（保存时表示不改动原值）。 */
    public static final String KEEP_KEY = "\u0000keep";

    // ------------------------------------------------------------ 提示词装配

    /**
     * 把「文字替换规则」与「句末后缀」两项设置展开成提示词里的一段文字。
     *
     * <p>这是用户要求的语义：开了 LLM 之后，这两项不再机械套用，
     * 而是作为「要求」告诉模型。
     */
    public static String systemPrompt() {
        String rules = rulesText();
        String suf = suffixText();
        return prompt.replace("{替换规则}", rules).replace("{句末后缀}", suf);
    }

    /** 启用的替换规则展开成清单。 */
    private static String rulesText() {
        List<Config.Rule> rs = Config.rules();
        StringBuilder sb = new StringBuilder();
        for (Config.Rule r : rs) {
            if (r.enabled && r.from != null && r.from.length() > 0
                    && r.to != null && !r.from.equals(r.to)) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(r.from).append(" → ").append(r.to);
            }
        }
        return sb.length() == 0 ? "（没有要求替换的词）" : sb.toString();
    }

    /** 句末后缀展开成文字描述。 */
    private static String suffixText() {
        if (!Config.suffixEnabled()) {
            return "（不加后缀）";
        }
        String s = Config.suffix();
        return s == null || s.length() == 0 ? "（不加后缀）" : s;
    }

    // ------------------------------------------------------------ 结果缓存

    /** 原句 → 改写后的正文。 */
    private static final Map<String, String> results =
            new LinkedHashMap<String, String>();

    /** 正在排队 / 请求中的原句。 */
    private static final Set<String> inflight =
            new LinkedHashSet<String>();

    /** 失败过的原句（负缓存，避免每轮重复请求）。 */
    private static final Set<String> failed =
            new LinkedHashSet<String>();

    /** 已经产出过的改写结果正文（识别「回流」用，见 {@link #isOutput}）。 */
    private static final Set<String> outputs =
            new LinkedHashSet<String>();

    /** 取出已经改写好的正文；没有则返回 null。 */
    public static synchronized String rewrite(String text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        String r = results.get(text);
        return (r == null || r.length() == 0) ? null : r;
    }

    /**
     * 这句是否「已经是模型改写过的正文」。
     *
     * <p>写回输入框之后，下一轮读到的就是改写结果本身，它当初不是送去改写的
     * 那个字符串。认出来就原样返回，否则每一轮都会重新请求一次模型
     * （既慢又白烧额度）。
     */
    public static synchronized boolean isOutput(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        return outputs.contains(text);
    }

    /** 把一条改写结果登记进「输出集合」，用于识别回流。 */
    public static synchronized void noteIdentity(String text) {
        if (text == null || text.length() == 0 || !enabled) {
            return;
        }
        if (outputs.size() >= CACHE_MAX * 4) {
            outputs.clear();
        }
        outputs.add(text);
    }

    /** 请模型改写（不等待）。 */
    public static void prefetch(final String text) {
        fetch(text, false);
    }

    /**
     * 请模型改写并最多等 {@code timeoutMs} 毫秒。
     *
     * @return 改写后的正文；超时 / 失败返回 null（调用方回退本地规则）
     */
    public static String consult(String text, long timeoutMs) {
        if (text == null || text.length() == 0) {
            return null;
        }
        fetch(text, true);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            String r = rewrite(text);
            if (r != null) {
                return r;
            }
            if (isFailed(text)) {
                return null;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return rewrite(text);
    }

    private static synchronized boolean isFailed(String text) {
        return failed.contains(text);
    }

    /** 这句是否已经确定改写失败（调用方不必再等）。 */
    public static boolean failedFor(String text) {
        return isFailed(text);
    }

    private static synchronized void fetch(String text, boolean force) {
        if (!enabled || !usable() || text == null || text.length() == 0) {
            return;
        }
        if (tooLong(text)) {
            lastStatus = "超长跳过（" + text.length() + "字 > " + maxInput + "字）";
            Cat.log("LLM 超长跳过 [" + text.length() + "字 > 上限 "
                    + maxInput + "字] 直接用本地规则");
            markFailed(text);
            return;
        }
        if (results.containsKey(text)) {
            // 缓存命中（内存或启动时从 llm_cache.txt 读进来的）
            lastStatus = "缓存命中：" + text;
            Cat.log("LLM 缓存命中 [" + text + "] -> " + results.get(text));
            return;
        }
        if (inflight.contains(text) || failed.contains(text)
                || outputs.contains(text)) {
            return;
        }
        inflight.add(text);
        ensureWorker();
        if (!queue.offer(text)) {
            inflight.remove(text);
        }
    }

    /** 清空全部缓存与负缓存。 */
    public static synchronized void resetCache() {
        results.clear();
        inflight.clear();
        failed.clear();
        outputs.clear();
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
                    try {
                        String job = queue.take();
                        handle(job);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable e) {
                        Cat.log("LLM 工作线程异常 " + e.getClass().getSimpleName());
                    }
                }
            }
        }, "qqcat-llm");
        t.setDaemon(true);
        t.start();
    }

    /** 处理一句：装配提示词 -> 请求 -> 存缓存。 */
    private static void handle(String job) {
        try {
            if (queue.size() > QUEUE_MAX) {
                synchronized (Llm.class) {
                    inflight.remove(job);
                }
                return;
            }
            String body = chat(systemPrompt(), job);
            synchronized (Llm.class) {
                inflight.remove(job);
                if (body == null || body.length() == 0) {
                    markFailed(job);
                    Cat.log("LLM 未命中 [" + job + "] " + lastStatus);
                    return;
                }
                if (results.size() >= CACHE_MAX) {
                    String first = null;
                    for (String k : results.keySet()) {
                        first = k;
                        break;
                    }
                    if (first != null) {
                        results.remove(first);
                    }
                }
                results.put(job, body);
                failed.remove(job);
                if (outputs.size() >= CACHE_MAX * 4) {
                    outputs.clear();
                }
                outputs.add(body);
                // 落到 llm_cache.txt：下次同样输入直接命中，不再请求模型
                cacheToDisk(job, body);
                Cat.log("LLM 已改写 [" + job + "] -> [" + body + "]");
            }
        } catch (Throwable t) {
            synchronized (Llm.class) {
                inflight.remove(job);
                markFailed(job);
            }
            Cat.log("LLM 处理异常 " + t.getClass().getSimpleName());
        }
    }

    private static void markFailed(String job) {
        if (failed.size() >= FAILED_MAX) {
            failed.clear();
        }
        failed.add(job);
    }

    // ------------------------------------------------------------ HTTP

    /**
     * 补全成真正的对话接口地址。
     *
     * <p>用户可能只填到 base（`https://api.siliconflow.cn/v1`），
     * 也可能直接填完整路径。两种都接受：
     * <ul>
     *   <li>以 `/chat/completions` 结尾 → 原样使用</li>
     *   <li>以 `/v1` 或 `/v1beta` 结尾 → 补 `/chat/completions`</li>
     *   <li>其它 → 补 `/v1/chat/completions`</li>
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
        if (u.endsWith("/chat/completions")) {
            return u;
        }
        if (u.endsWith("/v1") || u.endsWith("/v1beta")) {
            return u + "/chat/completions";
        }
        return u + "/v1/chat/completions";
    }

    /**
     * 调一次对话接口，取回改写后的正文。
     *
     * <p>兼容 `{"choices":[{"message":{"content":"..."}}]}`；
     * 任何失败都返回 null（调用方回退本地规则）。
     */
    public static String chat(String system, String user) {
        if (!usable() || user == null || user.length() == 0) {
            return null;
        }
        HttpURLConnection c = null;
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", system == null ? "" : system);
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", user);
            JSONArray msgs = new JSONArray();
            msgs.put(sys);
            msgs.put(usr);

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", msgs);
            body.put("temperature", temp);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);
            applyThinking(body);

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
            long t0 = System.currentTimeMillis();
            if (code < 200 || code >= 300) {
                String err = readAll(c.getErrorStream());
                lastStatus = "HTTP " + code;
                Cat.log("LLM HTTP " + code + " " + clip(err));
                return null;
            }
            String resp = readAll(c.getInputStream());
            String out = parseChat(resp);
            if (out == null) {
                // 空正文多半是「思考吃满了 max_tokens」：把 finish_reason 记下来，
                // 方便用户对照面板上的思考等级 / 最大输出自己调。
                String fin = finishOf(resp);
                lastStatus = "返回正文为空（" + fin + "）";
                Cat.log("LLM 返回空正文 finish=" + fin
                        + "，请检查最大输出（当前 " + maxTokens
                        + "）/ 思考等级（" + LEVEL_NAMES[level] + "） " + clip(resp));
                return null;
            }
            String clean = cleanup(out, user);
            if (clean == null) {
                lastStatus = "返回不合规 " + lastReject;
                Cat.log("LLM 返回被拒 [" + lastReject + "] "
                        + clip(out));
                return null;
            }
            lastStatus = "正常 " + (System.currentTimeMillis() - t0) + "ms";
            return clean;
        } catch (Throwable t) {
            lastStatus = "异常 " + t.getClass().getSimpleName();
            Cat.log("LLM 请求异常 " + t.getClass().getSimpleName() + " "
                    + clip(String.valueOf(t.getMessage())));
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable t) { }
            }
        }
    }

    /**
     * 按当前思考等级往请求体里塞参数。
     *
     * <p>「默认」档什么都不发，兼容性最好。其余档位发的都是**请求参数**，
     * 服务端不认识时会忽略或报错 —— 报错会记成 LLM HTTP 400/422。
     * 用户要求把这条写清楚在界面上，免得以为是 bug。
     */
    private static void applyThinking(JSONObject body) {
        int lv = level;
        if (lv <= 0 || lv >= LEVEL_VALUES.length) {
            return;   // 默认：不发送
        }
        String v = LEVEL_VALUES[lv];
        try {
            body.put("reasoning_effort", v);
            if ("none".equals(v)) {
                JSONObject th = new JSONObject();
                th.put("type", "disabled");
                body.put("thinking", th);
            }
        } catch (Throwable t) {
            Cat.log("LLM 思考参数装配失败 " + t.getClass().getSimpleName());
        }
    }

    /** 取 finish_reason（用于诊断「返回空正文」）。 */
    private static String finishOf(String resp) {
        try {
            JSONArray ch = new JSONObject(resp).optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                JSONObject c0 = ch.optJSONObject(0);
                if (c0 != null) {
                    String f = c0.optString("finish_reason", "");
                    return f.length() == 0 ? "未知" : f;
                }
            }
        } catch (Throwable t) {
            // 落空
        }
        return "未知";
    }

    /**
     * 从返回体里取出正文。
     */
    private static String parseChat(String resp) {
        if (resp == null || resp.length() == 0) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(resp);
            JSONArray ch = o.optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                JSONObject c0 = ch.optJSONObject(0);
                if (c0 != null) {
                    JSONObject msg = c0.optJSONObject("message");
                    if (msg != null) {
                        String c = msg.optString("content", "");
                        if (c.length() > 0) {
                            return c;
                        }
                    }
                    String txt = c0.optString("text", "");
                    if (txt.length() > 0) {
                        return txt;
                    }
                }
            }
            // 简化风格：{"output": "..."} / {"text": "..."}
            String s = o.optString("output", "");
            if (s.length() > 0) {
                return s;
            }
            s = o.optString("text", "");
            if (s.length() > 0) {
                return s;
            }
        } catch (Throwable t) {
            // 落空
        }
        return null;
    }

    /**
     * 清洗模型输出。
     *
     * <p>去掉 markdown 代码围栏、首尾引号、换行，只保留第一段。
     * 另外做一次「离谱」检查：模型有时会不听话地回答问题或长篇大论，
     * 长度明显超出原文太多时宁可不用，回退本地规则。
     */
    private static String cleanup(String raw, String input) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        // 去掉 ``` 围栏
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        // 只取第一行（模型偶尔会附解释）
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).trim();
        }
        // 去掉首尾引号
        while (s.length() >= 2) {
            char c0 = s.charAt(0);
            char c1 = s.charAt(s.length() - 1);
            if ((c0 == '"' && c1 == '"') || (c0 == '\'' && c1 == '\'')
                    || (c0 == '「' && c1 == '」') || (c0 == '“' && c1 == '”')) {
                s = s.substring(1, s.length() - 1).trim();
            } else {
                break;
            }
        }
        if (s.length() == 0) {
            lastReject = "清洗后为空";
            return null;
        }
        // 离谱检查：改写后不该比原文长太多。
        //
        // 注意下限：激进提示词允许扩写，短句（「额。」「继续修」）被扩到
        // 30~40 字是正常输出，早先的 inLen*3+20 会把它们整条丢掉
        //（2 字输入只允许 26 字），表现为「返回被拒 → 回退本地规则」。
        // 这里给一个 120 字的绝对值下限，只有真正跑飞的超长正文才拒。
        int inLen = input == null ? 0 : input.length();
        int limit = Math.max(inLen * 3 + 20, MIN_OUTPUT_LEN);
        if (s.length() > limit) {
            lastReject = "输出超长（" + s.length() + "字 > 上限 "
                    + limit + "字，原文 " + inLen + " 字）";
            return null;
        }
        lastReject = "";
        return s;
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 200) {
                sb.append(line).append('\n');
                n++;
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    /** 界面「测试连接」用：拿一句样例走一遍完整流程。 */
    public static String test(String sample) {
        if (!usable()) {
            return "请先填写接口地址、模型名和 API Key";
        }
        String out = chat(systemPrompt(), sample);
        if (out == null) {
            return "失败：" + lastStatus;
        }
        return "正常\n输入：" + sample + "\n输出：" + out;
    }
}
