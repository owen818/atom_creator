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
 * DeepSeek OpenAI-compatible API adapter. API keys remain on the server and
 * are supplied only via DEEPSEEK_API_KEY, never sent to the Vue application.
 */
@Service
public class DeepSeekService {
  private final String key, model;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  public DeepSeekService(@Value("${atoms.deepseek.api-key}") String key, @Value("${atoms.deepseek.model}") String model) {
    this.key = key;
    this.model = model;
  }

  /**
   * Generates or revises one application. Previous source and requirements are
   * deliberately supplied for revisions so a small request cannot discard features.
   */
  public Result generate(String prompt, String currentHtml, List<String> history, String changeType) {
    if (key == null || key.isBlank()) return fallback(prompt, currentHtml, "set DEEPSEEK_API_KEY to use DeepSeek");
    try {
      String instruction = buildInstruction(prompt, currentHtml, history, changeType);
      Map<String, Object> body = Map.of(
          "model", model,
          "messages", List.of(Map.of("role", "user", "content", instruction)),
          "temperature", 0.7);
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + key)
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .timeout(Duration.ofSeconds(55))
          .build();
      JsonNode root = json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
      String html = root.at("/choices/0/message/content").asText();
      if (html.isBlank()) throw new IllegalStateException(root.path("error").path("message").asText("DeepSeek returned no content"));
      return new Result(html.replace("```html", "").replace("```", "").trim(), "DeepSeek · " + model);
    } catch (Exception e) {
      return fallback(prompt, currentHtml, "DeepSeek unavailable: " + e.getMessage());
    }
  }

  /** A failed incremental call must preserve the existing artifact rather than silently remove features. */
  private Result fallback(String prompt, String currentHtml, String reason) {
    if (currentHtml != null && !currentHtml.isBlank()) return new Result(currentHtml, "Local fallback · previous version preserved: " + reason);
    return new Result(demoHtml(prompt), "Local demo fallback (" + reason + ")");
  }

  private String buildInstruction(String prompt, String currentHtml, List<String> history, String changeType) {
    String prior = history.isEmpty() ? "No prior requirements." : String.join("\n- ", history);
    String base = currentHtml == null || currentHtml.isBlank() ? "No current source; create a new application." : currentHtml.substring(0, Math.min(currentHtml.length(), 60_000));
    return "You are an expert product engineer. Return ONLY one complete, safe, standalone HTML document (inline CSS and JavaScript allowed). "
        + "Change mode: " + changeType + ". Current user request: " + prompt + "\n"
        + "Prior requirements (preserve them unless explicitly changed):\n- " + prior + "\n"
        + "Current application source:\n" + base + "\n"
        + "For MODIFY or BUGFIX, edit the current source instead of rebuilding it. Preserve existing features, element IDs, forms, and interactions unless the request explicitly removes them. "
        + "For BUGFIX, fix the reported behavior and keep unrelated behavior working.";
  }

  /** Produces a reviewable plan before source generation; callers persist and let users edit it. */
  public String plan(String prompt, String currentHtml, List<String> history, String changeType) {
    String fallback = "1. 保留当前版本的既有功能和页面结构。\n2. 根据本次需求实施 " + changeType + " 改动。\n3. 生成完整可运行 HTML。\n4. 比较按钮、表单和脚本，执行结构回归检查。";
    if (key == null || key.isBlank()) return fallback;
    try {
      String request = "Return only a concise numbered Chinese implementation plan (3-5 steps). Do not generate code. Change mode: " + changeType
          + ". New request: " + prompt + ". Historical requirements: " + String.join(" | ", history)
          + ". Current source exists: " + (currentHtml != null && !currentHtml.isBlank()) + ". The plan must preserve unaffected features and include regression verification.";
      Map<String,Object> body=Map.of("model",model,"messages",List.of(Map.of("role","user","content",request)),"temperature",0.2);
      HttpRequest http=HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions")).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).timeout(Duration.ofSeconds(30)).build();
      JsonNode root=json.readTree(client.send(http,HttpResponse.BodyHandlers.ofString()).body()); String text=root.at("/choices/0/message/content").asText().trim();
      return text.isBlank()?fallback:text.replace("```","");
    } catch (Exception ignored) { return fallback; }
  }

  /** A deterministic interactive artifact used only when a model key is unavailable. */
  private String demoHtml(String prompt) {
    String safe = prompt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return "<!doctype html><html><head><meta charset='utf-8'><style>body{margin:0;font-family:Inter,Arial;background:#101827;color:#edf4ff}.wrap{max-width:760px;margin:60px auto;padding:32px}.tag{color:#7dd3fc;text-transform:uppercase;letter-spacing:2px}.card{background:#19243a;border:1px solid #334765;border-radius:20px;padding:28px;margin-top:22px}button{background:#31d0aa;border:0;border-radius:9px;padding:12px 18px;font-weight:bold;cursor:pointer}.count{font-size:52px;margin:18px 0}</style></head><body><main class='wrap'><div class='tag'>Atoms generated app</div><h1>" + safe + "</h1><section class='card'><p>This interactive preview was generated by the local resilient demo mode.</p><div class='count' id='n'>0</div><button id='progress' type='button'>Make progress</button></section></main><script>document.getElementById('progress').addEventListener('click',function(){var counter=document.getElementById('n');counter.textContent=String(Number(counter.textContent)+1);});</script></body></html>";
  }

  public record Result(String html, String provider) { }
}
