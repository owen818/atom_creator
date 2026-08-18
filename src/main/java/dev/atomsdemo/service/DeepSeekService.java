package dev.atomsdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DeepSeek OpenAI 兼容接口适配器。
 *
 * <p>做什么：把「产品描述 + 当前 HTML + 历史需求」组装成 prompt，调用 DeepSeek {@code chat/completions} 生成完整单页
 * HTML，或在失败时回退到本地演示页。 何时用到：{@code ApiController} 同步生成（{@code /projects/generate}）、 生成可编辑计划（{@code
 * /projects/plan}）、以及 Agent 异步执行（{@code executeRun}）时。 API 密钥只存在服务端，通过 {@code DEEPSEEK_API_KEY}
 * 注入，不会发给 Vue 前端。
 */
@Service
public class DeepSeekService {
  private final String key, model;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  /**
   * 从 Spring 配置注入模型凭证。
   *
   * <p>做什么：保存 API Key 和模型名（默认 {@code deepseek-chat}），供后续 HTTP 调用使用。 何时用到：应用启动、Spring 创建本 Service
   * Bean 时自动调用。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * // application.properties:
   * // atoms.deepseek.api-key=${DEEPSEEK_API_KEY:}
   * // atoms.deepseek.model=${DEEPSEEK_MODEL:deepseek-chat}
   * DeepSeekService svc = new DeepSeekService("sk-xxx", "deepseek-chat");
   * }</pre>
   *
   * @param key {@code atoms.deepseek.api-key}，通常来自环境变量 {@code DEEPSEEK_API_KEY}
   * @param model {@code atoms.deepseek.model}，如 {@code deepseek-chat}
   */
  public DeepSeekService(
      @Value("${atoms.deepseek.api-key}") String key,
      @Value("${atoms.deepseek.model}") String model) {
    this.key = key;
    this.model = model;
  }

  /**
   * 根据用户需求生成或修订一份完整 HTML 应用。
   *
   * <p>做什么：无密钥或调用失败时走本地回退；成功则返回模型 HTML 并去掉 markdown 围栏。 何时用到：同步接口 {@code POST
   * /api/projects/generate}，以及 Agent 批准后的 {@code executeRun}。
   *
   * <h3>算法与分支</h3>
   *
   * <ol>
   *   <li><b>密钥门闩</b>：{@code key} 为空 → 立即 {@link #fallback}，不发网络请求。
   *   <li><b>组装 prompt</b>：{@link #buildInstruction} 把 changeType、本次需求、最近历史、当前 HTML（最多 60_000
   *       字符）拼成一条 user message。
   *   <li><b>调用模型</b>：POST {@code https://api.deepseek.com/chat/completions}， Authorization
   *       Bearer、timeout 55s、temperature 0.7（偏创造）。
   *   <li><b>抽取正文</b>：JSON 路径 {@code /choices/0/message/content}；空内容视为失败，改读 {@code /error/message}。
   *   <li><b>清洗</b>：去掉 {@code ```html} 和 {@code ```}，trim。模型常把 HTML 包在代码块里。
   *   <li><b>异常回退</b>：超时、鉴权失败、JSON 解析失败、空内容全部 catch，交给 {@link #fallback}。 增量场景会原样返回 {@code
   *       currentHtml}，避免「生成失败却把旧功能抹掉」。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * // 新建
   * Result created = deepSeek.generate("做一个番茄钟", "", List.of(), "CREATE");
   *
   * // 增量修改：带上当前 HTML 和最近需求，避免丢掉旧功能
   * Result revised = deepSeek.generate(
   *     "再加一个暂停按钮",
   *     created.html(),
   *     List.of("做一个番茄钟"),
   *     "MODIFY");
   * // revised.provider() 形如 "DeepSeek · deepseek-chat"
   * // 若未配置 DEEPSEEK_API_KEY 且有 currentHtml，则 html 仍是上一版
   * }</pre>
   *
   * @param prompt 本次自然语言需求
   * @param currentHtml 当前最新版本 HTML；新建时为空
   * @param history 最近若干条历史需求，用于防止小改动丢掉旧功能
   * @param changeType {@code CREATE} / {@code MODIFY} / {@code BUGFIX}
   * @return 生成结果：HTML 正文以及实际使用的 provider 说明
   */
  public Result generate(
      String prompt, String currentHtml, List<String> history, String changeType) {
    if (key == null || key.isBlank())
      return fallback(prompt, currentHtml, "set DEEPSEEK_API_KEY to use DeepSeek");
    try {
      String instruction = buildInstruction(prompt, currentHtml, history, changeType);
      Map<String, Object> body =
          Map.of(
              "model",
              model,
              "messages",
              List.of(Map.of("role", "user", "content", instruction)),
              "temperature",
              0.7);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + key)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .timeout(Duration.ofSeconds(55))
              .build();
      JsonNode root =
          json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
      String html = root.at("/choices/0/message/content").asText();
      if (html.isBlank())
        throw new IllegalStateException(
            root.path("error").path("message").asText("DeepSeek returned no content"));
      return new Result(
          html.replace("```html", "").replace("```", "").trim(), "DeepSeek · " + model);
    } catch (Exception e) {
      return fallback(prompt, currentHtml, "DeepSeek unavailable: " + e.getMessage());
    }
  }

  /**
   * 模型不可用时的安全回退。
   *
   * <p>做什么：有旧 HTML 则原样保留；没有则生成本地演示页。失败原因写进 {@code provider}。 何时用到：未配置 API Key、DeepSeek
   * 超时、鉴权失败或返回空内容时，由 {@link #generate} 调用。
   *
   * <h3>算法</h3>
   *
   * 决策树（保证增量失败不丢功能）：
   *
   * <pre>
   * currentHtml 非空？
   *   ├─ 是 → 返回同一份 HTML，provider = "Local fallback · previous version preserved: {reason}"
   *   └─ 否 → 调用 {@link #demoHtml} 造一份可点击计数器页，provider = "Local demo fallback ({reason})"
   * </pre>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * fallback("计数器", "<html>...</html>", "timeout");
   * // → html 仍是传入的上一版，不会变成空白页
   *
   * fallback("计数器", "", "no api key");
   * // → html 是本地 demo 页，标题为「计数器」
   * }</pre>
   *
   * @param prompt 原始需求，用于演示页标题
   * @param currentHtml 已有产物；可为空
   * @param reason 回退原因，会写进 provider 字段方便前端展示
   * @return 回退用的 {@link Result}
   */
  private Result fallback(String prompt, String currentHtml, String reason) {
    if (currentHtml != null && !currentHtml.isBlank())
      return new Result(currentHtml, "Local fallback · previous version preserved: " + reason);
  }

  /**
   * 把业务上下文拼成发给模型的用户指令。
   *
   * <p>做什么：写入变更模式、本次需求、最近历史需求、以及截断后的当前源码。 何时用到：每次真正调用 DeepSeek 生成代码前，由 {@link #generate} 调用。
   *
   * <h3>拼装算法</h3>
   *
   * <ol>
   *   <li>历史：空列表 → {@code "No prior requirements."}；否则用 {@code "\n- "} 连接成无序列表。
   *   <li>源码：空 → 提示「从零创建」；非空 → {@code substring(0, min(len, 60000))}，防止超大 HTML 撑爆请求体。
   *   <li>角色约束：只返回一份完整独立 HTML（允许内联 CSS/JS），不要解释文字。
   *   <li>{@code MODIFY}/{@code BUGFIX} 额外约束：在现有源码上改，保留 element id、表单和交互； BUGFIX 只修报出的行为，其它功能保持可用。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * String inst = buildInstruction(
   *     "加一个重置按钮",
   *     "<html><button>start</button></html>",
   *     List.of("做一个计时器"),
   *     "MODIFY");
   * // inst 中会包含 Change mode: MODIFY、Current user request、Prior requirements、Current application source
   * }</pre>
   *
   * @param prompt 本次需求
   * @param currentHtml 当前 HTML，过长时截到约 6 万字符
   * @param history 历史需求列表
   * @param changeType 变更类型
   * @return 完整英文指令字符串
   */
  private String buildInstruction(
      String prompt, String currentHtml, List<String> history, String changeType) {
    String prior = history.isEmpty() ? "No prior requirements." : String.join("\n- ", history);
    String base =
        currentHtml == null || currentHtml.isBlank()
            ? "No current source; create a new application."
            : currentHtml.substring(0, Math.min(currentHtml.length(), 60_000));
    return "You are an expert product engineer. Return ONLY one complete, safe, standalone HTML"
               + " document (inline CSS and JavaScript allowed). Change mode: "
        + changeType
        + ". Current user request: "
        + prompt
        + "\n"
        + "Prior requirements (preserve them unless explicitly changed):\n- "
        + prior
        + "\n"
        + "Current application source:\n"
        + base
        + "\n"
        + "For MODIFY or BUGFIX, edit the current source instead of rebuilding it. Preserve"
        + " existing features, element IDs, forms, and interactions unless the request explicitly"
        + " removes them. For BUGFIX, fix the reported behavior and keep unrelated behavior"
        + " working.";
  }

  /**
   * 在真正写代码之前，让模型产出一份可审阅的实施计划。
   *
   * <p>做什么：请求 3–5 条中文编号步骤（不含代码）；无密钥或调用失败则返回固定模板计划。 何时用到：用户点击「生成计划」时，{@code POST /api/projects/plan}
   * 调用本方法； 结果会写入 {@code agent_runs.plan}，用户可编辑后再批准执行。
   *
   * <h3>算法与分支</h3>
   *
   * <ol>
   *   <li>先构造 4 步中文模板（保留结构 → 按 changeType 改 → 生成 HTML → 结构回归），作为永远可用的 fallback。
   *   <li>无密钥：直接返回模板，不发 HTTP。
   *   <li>有密钥：temperature 0.2（更稳、更短），timeout 30s（计划比写代码快）， 要求「只返回中文编号步骤、不要代码、必须包含回归验证」。
   *   <li>历史需求用 {@code " | "} 拼成一行，避免过长；只传「当前是否已有源码」布尔值，不传整份 HTML。
   *   <li>空回复或任意异常 → 回退到模板；成功则去掉 markdown 围栏。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * String plan = deepSeek.plan(
   *     "把提交按钮改成绿色",
   *     "<html><form>...</form></html>",
   *     List.of("做一个报名表"),
   *     "MODIFY");
   * // 可能返回：
   * // 1. 保留当前表单字段和校验
   * // 2. 将提交按钮样式改为绿色
   * // 3. 生成完整可运行 HTML
   * // 4. 比较按钮、表单和脚本数量
   * }</pre>
   *
   * @param prompt 本次需求
   * @param currentHtml 当前 HTML，用于判断是否已有源码
   * @param history 历史需求
   * @param changeType 变更类型
   * @return 中文实施计划文本
   */
  public String plan(String prompt, String currentHtml, List<String> history, String changeType) {
    String fallback =
        "1. 保留当前版本的既有功能和页面结构。\n2. 根据本次需求实施 "
            + changeType
            + " 改动。\n3. 生成完整可运行 HTML。\n4. 比较按钮、表单和脚本，执行结构回归检查。";
    if (key == null || key.isBlank()) return fallback;
    try {
      String request =
          "Return only a concise numbered Chinese implementation plan (3-5 steps). Do not generate"
              + " code. Change mode: "
              + changeType
              + ". New request: "
              + prompt
              + ". Historical requirements: "
              + String.join(" | ", history)
              + ". Current source exists: "
              + (currentHtml != null && !currentHtml.isBlank())
              + ". The plan must preserve unaffected features and include regression verification.";
      Map<String, Object> body =
          Map.of(
              "model",
              model,
              "messages",
              List.of(Map.of("role", "user", "content", request)),
              "temperature",
              0.2);
      HttpRequest http =
          HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + key)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .timeout(Duration.ofSeconds(30))
              .build();
      JsonNode root = json.readTree(client.send(http, HttpResponse.BodyHandlers.ofString()).body());
      String text = root.at("/choices/0/message/content").asText().trim();
      return text.isBlank() ? fallback : text.replace("```", "");
    } catch (Exception ignored) {
      return fallback;
    }
  }

  /**
   * 本地确定性演示页：无模型密钥时也能预览交互。
   *
   * <p>做什么：把用户描述做 HTML 转义后嵌入深色卡片页，并带一个可点击的计数按钮。 何时用到：仅由 {@link #fallback} 在「没有上一版
   * HTML」时调用，例如首次创建且未配置 Key。
   *
   * <h3>算法</h3>
   *
   * <ol>
   *   <li>转义 {@code & < >}，防止 prompt 注入破坏页面结构（简单 HTML 实体替换，不是完整 sanitizer）。
   *   <li>拼一份独立 HTML：标题 = 转义后的 prompt，按钮 {@code #progress} 点击后把 {@code #n} 数字 +1。
   *   <li>输出确定、无随机，同一 prompt 永远得到同一页，方便评审走通流程。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * String html = demoHtml("Hello <script>alert(1)</script>");
   * // 标题里是 Hello &lt;script&gt;alert(1)&lt;/script&gt;，脚本不会执行
   * }</pre>
   *
   * @param prompt 用户描述，会显示为页面标题
   * @return 完整独立 HTML 字符串
   */
  private String demoHtml(String prompt) {
    String safe = prompt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return "<!doctype html><html><head><meta"
               + " charset='utf-8'><style>body{margin:0;font-family:Inter,Arial;background:#101827;color:#edf4ff}.wrap{max-width:760px;margin:60px"
               + " auto;padding:32px}.tag{color:#7dd3fc;text-transform:uppercase;letter-spacing:2px}.card{background:#19243a;border:1px"
               + " solid"
               + " #334765;border-radius:20px;padding:28px;margin-top:22px}button{background:#31d0aa;border:0;border-radius:9px;padding:12px"
               + " 18px;font-weight:bold;cursor:pointer}.count{font-size:52px;margin:18px"
               + " 0}</style></head><body><main class='wrap'><div class='tag'>Atoms generated"
               + " app</div><h1>"
        + safe
        + "</h1><section class='card'><p>This interactive preview was generated by the local"
        + " resilient demo mode.</p><div class='count' id='n'>0</div><button id='progress'"
        + " type='button'>Make"
        + " progress</button></section></main><script>document.getElementById('progress').addEventListener('click',function(){var"
        + " counter=document.getElementById('n');counter.textContent=String(Number(counter.textContent)+1);});</script></body></html>";
  }

  /**
   * 一次代码生成的结果载体。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * Result r = new Result("<!doctype html>...", "DeepSeek · deepseek-chat");
   * iframe.srcdoc = r.html();
   * ui.showProvider(r.provider());
   * }</pre>
   *
   * @param html 可直接放入 iframe 预览的完整 HTML
   * @param provider 实际来源说明，如 {@code DeepSeek · deepseek-chat} 或本地回退原因
   */
  public record Result(String html, String provider) {}
}
