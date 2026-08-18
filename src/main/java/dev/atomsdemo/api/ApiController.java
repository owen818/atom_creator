package dev.atomsdemo.api;

import dev.atomsdemo.service.DeepSeekService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 演示工作台的 REST API：身份、项目历史、代码生成与 Agent 执行。
 *
 * <p>前端通过 {@code /api} 完成注册登录、列项目、预览/导出产物、同步生成， 以及「先出计划 → 编辑 → 批准异步执行 → 轮询状态」的 Agent 流程。 用户身份用请求头
 * {@code X-User-Id} 标识（演示用，浏览器本地保存登录后的用户 id）。
 */
@RestController
@RequestMapping("/api")
public class ApiController {
  private final JdbcTemplate db;
  private final DeepSeekService deepSeek;

  /**
   * 注入数据库访问和 DeepSeek 适配器。
   *
   * <p>做什么：保存 {@link JdbcTemplate}（SQLite）和 {@link DeepSeekService}，供各接口使用。 何时用到：Spring 创建
   * Controller Bean 时自动调用。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * // 由 Spring 自动装配，无需手写。等价于：
   * new ApiController(jdbcTemplate, deepSeekService);
   * }</pre>
   *
   * @param db 操作 users / projects / generations / agent_runs 表
   * @param deepSeek 调用模型生成计划或 HTML
   */
  public ApiController(JdbcTemplate db, DeepSeekService deepSeek) {
    this.db = db;
    this.deepSeek = deepSeek;
  }

  /**
   * 注册新用户。
   *
   * <p>做什么：校验邮箱和密码；写入 {@code users}（密码 SHA-256）；邮箱重复返回 409。 何时用到：前端注册页提交，{@code POST
   * /api/auth/register}。
   *
   * <h3>算法</h3>
   *
   * <ol>
   *   <li>校验：邮箱非空且含 {@code @}；密码非空且长度 ≥ 6。失败 → 400。
   *   <li>姓名空白则默认 {@code "Builder"}；邮箱转小写后入库，避免 {@code A@x.com} 与 {@code a@x.com} 当成两人。
   *   <li>{@link #hash} 后 INSERT；UNIQUE(email) 冲突被 catch 成 409「该邮箱已注册」。
   *   <li>用生成的主键 {@link #user} 回读公开字段，绝不返回 password_hash。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * POST /api/auth/register
   * {"name":"Owen","email":"owen@example.com","password":"secret1"}
   * // 200 → {"id":1,"name":"Owen","email":"owen@example.com"}
   *
   * // 密码太短 → 400；邮箱已存在 → 409
   * }</pre>
   *
   * @param body 姓名、邮箱、明文密码
   * @return {@code id / name / email}
   */
  @PostMapping("/auth/register")
  public Map<String, Object> register(@RequestBody Auth body) {
    if (body.email() == null
        || !body.email().contains("@")
        || body.password() == null
        || body.password().length() < 6)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效邮箱和至少 6 位密码");
    try {
      long id =
          insert(
              "INSERT INTO users(name,email,password_hash,created_at) VALUES(?,?,?,?)",
              body.name() == null || body.name().isBlank() ? "Builder" : body.name(),
              body.email().toLowerCase(),
              hash(body.password()),
              Instant.now().toString());
      return user(id);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已注册");
    }
  }

  /**
   * 用邮箱和密码登录。
   *
   * <p>做什么：按小写邮箱 + 密码哈希查询用户；找不到则 401。 何时用到：前端登录页提交，{@code POST /api/auth/login}。浏览器把返回的 {@code id}
   * 存为后续请求的 {@code X-User-Id}。
   *
   * <h3>算法</h3>
   *
   * 无盐 SHA-256 比对（演示用）：{@code hash(明文)} 必须与库中 {@code password_hash} 完全相等。 找不到行 →
   * 401（不区分「邮箱不存在」和「密码错」，避免枚举账号）。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * POST /api/auth/login
   * {"email":"owen@example.com","password":"secret1"}
   * // 200 → {"id":1,"name":"Owen","email":"owen@example.com"}
   * // 之后所有 /api/projects* 带 Header: X-User-Id: 1
   * }</pre>
   *
   * @param body 邮箱与明文密码
   * @return 登录成功的用户信息
   */
  @PostMapping("/auth/login")
  public Map<String, Object> login(@RequestBody Auth body) {
    List<Long> users =
        db.query(
            "SELECT id FROM users WHERE email=? AND password_hash=?",
            (rs, n) -> rs.getLong(1),
            body.email().toLowerCase(),
            hash(body.password()));
    if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
    return user(users.getFirst());
  }

  /**
   * 列出当前用户的全部项目及版本数量。
   *
   * <p>做什么：按 {@code user_id} 查项目，左连接 generations 统计版本数，按 {@code updated_at} 倒序。
   * 何时用到：工作台侧边栏加载列表，{@code GET /api/projects}。
   *
   * <h3>算法</h3>
   *
   * {@code LEFT JOIN generations} + {@code COUNT(g.id) AS versions}：没有生成记录的项目 versions=0，不会被 INNER
   * JOIN 丢掉。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET /api/projects
   * X-User-Id: 1
   * // → [{"id":3,"title":"做一个番茄钟…","status":"READY","versions":2,...}, ...]
   * }</pre>
   *
   * @param uid 请求头 {@code X-User-Id}
   * @return 项目摘要列表
   */
  @GetMapping("/projects")
  public List<Map<String, Object>> projects(@RequestHeader("X-User-Id") long uid) {
    return db.queryForList(
        "SELECT p.id,p.title,p.prompt,p.status,p.created_at,p.updated_at,COUNT(g.id) AS versions"
            + " FROM projects p LEFT JOIN generations g ON g.project_id=p.id WHERE p.user_id=?"
            + " GROUP BY p.id ORDER BY p.updated_at DESC",
        uid);
  }

  /**
   * 读取单个项目详情，含生成历史和 Agent 运行记录。
   *
   * <p>做什么：{@link #owned} 校验归属后，附上该项目全部 generations 与 agent_runs。 何时用到：打开某个项目，{@code GET
   * /api/projects/{id}}。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET /api/projects/3
   * X-User-Id: 1
   * // → {id,title,prompt,status,..., generations:[{version:2,...}], agentRuns:[{status:"COMPLETED",...}]}
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   * @return 项目字段 + {@code generations} + {@code agentRuns}
   */
  @GetMapping("/projects/{id}")
  public Map<String, Object> project(
      @RequestHeader("X-User-Id") long uid, @PathVariable("id") long id) {
    Map<String, Object> p = owned(uid, id);
    p.put(
        "generations",
        db.queryForList(
            "SELECT id,version,prompt,provider,created_at FROM generations WHERE project_id=? ORDER"
                + " BY version DESC",
            id));
    p.put(
        "agentRuns",
        db.queryForList(
            "SELECT"
                + " id,change_type,prompt,status,stage,plan,trace,regression,result_version,created_at,updated_at"
                + " FROM agent_runs WHERE project_id=? ORDER BY id DESC",
            id));
    return p;
  }

  /**
   * 取某一版生成结果的 HTML，供 iframe 预览。
   *
   * <p>做什么：校验归属后查 generations；未指定 version 取最新；没有产物则 404。 何时用到：预览面板刷新或切换历史版本，{@code GET
   * /api/projects/{id}/preview}。
   *
   * <h3>算法</h3>
   *
   * 动态 SQL：{@code version == null} → {@code ORDER BY version DESC LIMIT 1}；否则 {@code AND
   * version=?}。 两套 {@code queryForList} 绑定参数个数不同，不能共用同一调用。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET /api/projects/3/preview          // 最新版
   * GET /api/projects/3/preview?version=1 // 指定 v1
   * // → {"html":"<!doctype html>...","provider":"DeepSeek · deepseek-chat","version":2}
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   * @param version 可选版本号；空表示最新
   * @return {@code html / provider / version}
   */
  @GetMapping("/projects/{id}/preview")
  public Map<String, Object> preview(
      @RequestHeader("X-User-Id") long uid,
      @PathVariable("id") long id,
      @RequestParam(required = false) Integer version) {
    owned(uid, id);
    String sql =
        "SELECT html,provider,version FROM generations WHERE project_id=? "
            + (version == null ? "ORDER BY version DESC LIMIT 1" : "AND version=?");
    List<Map<String, Object>> result =
        version == null ? db.queryForList(sql, id) : db.queryForList(sql, id, version);
    if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无生成结果");
    return result.getFirst();
  }

  /**
   * 以纯文本返回某一版源码，便于审阅或复制。
   *
   * <p>做什么：复用 {@link #artifact} 取出 HTML，Content-Type 为 {@code text/plain}。 何时用到：用户点击「查看代码」，{@code
   * GET /api/projects/{id}/code}。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET /api/projects/3/code?version=2
   * // 响应体就是完整 HTML 字符串，不是 JSON
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   * @param version 可选版本号
   * @return 完整 HTML 源码
   */
  @GetMapping(value = "/projects/{id}/code", produces = MediaType.TEXT_PLAIN_VALUE)
  public String code(
      @RequestHeader("X-User-Id") long uid,
      @PathVariable("id") long id,
      @RequestParam(required = false) Integer version) {
    return artifact(uid, id, version).get("html").toString();
  }

  /**
   * 导出一版可运行产物为 ZIP（含 index.html 和简要 README）。
   *
   * <p>做什么：打包 HTML 与项目/版本/provider 说明；不包含服务端密钥。 何时用到：用户点击「导出」，{@code GET
   * /api/projects/{id}/export}。
   *
   * <h3>算法</h3>
   *
   * <ol>
   *   <li>{@link #artifact} 取出指定/最新 HTML 与 version、provider。
   *   <li>内存 {@code ZipOutputStream} 写入两个 entry：{@code index.html}（UTF-8）和 {@code README.md}（项目 id
   *       / 版本 / provider）。
   *   <li>{@code Content-Disposition: attachment; filename=atoms-project-{id}-v{v}.zip} 触发浏览器下载。
   *   <li>压缩异常统一变成 500「导出失败」。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET /api/projects/3/export?version=2
   * // 下载 atoms-project-3-v2.zip
   * // zip 内：index.html + README.md
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   * @param version 可选版本号
   * @return ZIP 字节流响应
   */
  @GetMapping("/projects/{id}/export")
  public ResponseEntity<byte[]> export(
      @RequestHeader("X-User-Id") long uid,
      @PathVariable("id") long id,
      @RequestParam(required = false) Integer version) {
    Map<String, Object> item = artifact(uid, id, version);
    int v = ((Number) item.get("version")).intValue();
    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("index.html"));
      zip.write(item.get("html").toString().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("README.md"));
      zip.write(
          ("# Generated application\n\nProject ID: "
                  + id
                  + "\nVersion: "
                  + v
                  + "\nProvider: "
                  + item.get("provider")
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.finish();
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=atoms-project-" + id + "-v" + v + ".zip")
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .body(out.toByteArray());
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出失败");
    }
  }

  /**
   * 同步生成一版应用（不经过「计划审批」流程）。HTTP 请求会阻塞到模型返回。
   *
   * <p>何时用到：前端走「直接生成」路径，{@code POST /api/projects/generate}。 Agent 可观察流程请用 {@link #plan} + {@link
   * #approve}。
   *
   * <h3>算法（逐步）</h3>
   *
   * <ol>
   *   <li>prompt 空白 → 400。
   *   <li>changeType 缺省为 {@code CREATE}。{@code CREATE} 或未带 projectId → INSERT
   *       新项目（status=GENERATING）；否则用已有 id。
   *   <li>{@link #owned} 防止改别人的项目；再 UPDATE prompt/status。
   *   <li>上下文：新项目 history/currentHtml 都为空；否则取最近 8 条 prompt + 最新 HTML。
   *   <li>{@code deepSeek.generate(...)} 得到 HTML（失败时服务内部会 fallback）。
   *   <li>版本号 = {@code MAX(version)+1}（没有记录则为 1）。INSERT 新 generation（不可变，旧行不覆盖）。
   *   <li>项目 status → READY；返回 projectId、version、provider、三步英文 trace、{@link #regression} 结果。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * // 新建
   * POST /api/projects/generate
   * X-User-Id: 1
   * {"prompt":"做一个番茄钟","changeType":"CREATE"}
   * // → {"projectId":3,"version":1,"provider":"...","trace":[...],"regression":{"status":"BASELINE",...}}
   *
   * // 在现有项目上增量改
   * {"projectId":3,"prompt":"加暂停按钮","changeType":"MODIFY"}
   * // → version:2，regression 会比较按钮/表单/脚本数量
   * }</pre>
   *
   * @param uid 当前用户
   * @param body 需求、可选项目 id、变更类型
   * @return 新版本元数据
   */
  @PostMapping("/projects/generate")
  public Map<String, Object> generate(
      @RequestHeader("X-User-Id") long uid, @RequestBody Generate body) {
    if (body.prompt() == null || body.prompt().isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "描述不能为空");
    String now = Instant.now().toString();
    String requestedType = body.changeType() == null ? "CREATE" : body.changeType();
    boolean freshProject = "CREATE".equals(requestedType);
    long projectId =
        (freshProject || body.projectId() == null)
            ? insert(
                "INSERT INTO projects(user_id,title,prompt,status,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?)",
                uid,
                title(body.prompt()),
                body.prompt(),
                "GENERATING",
                now,
                now)
            : body.projectId();
    owned(uid, projectId);
    db.update(
        "UPDATE projects SET status='GENERATING',prompt=?,updated_at=? WHERE id=?",
        body.prompt(),
        now,
        projectId);
    List<String> history =
        freshProject
            ? List.of()
            : db.query(
                "SELECT prompt FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 8",
                (rs, n) -> rs.getString(1),
                projectId);
    List<Map<String, Object>> previous =
        freshProject
            ? List.of()
            : db.queryForList(
                "SELECT html FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 1",
                projectId);
    String currentHtml = previous.isEmpty() ? "" : previous.getFirst().get("html").toString();
    String changeType = requestedType;
    DeepSeekService.Result result =
        deepSeek.generate(body.prompt(), currentHtml, history, changeType);
    Integer last =
        db.queryForObject(
            "SELECT COALESCE(MAX(version),0) FROM generations WHERE project_id=?",
            Integer.class,
            projectId);
    int v = (last == null ? 0 : last) + 1;
    insert(
        "INSERT INTO generations(project_id,version,prompt,html,provider,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        projectId,
        v,
        body.prompt(),
        result.html(),
        result.provider(),
        Instant.now().toString());
    db.update(
        "UPDATE projects SET status='READY',updated_at=? WHERE id=?",
        Instant.now().toString(),
        projectId);
    return Map.of(
        "projectId", projectId,
        "version", v,
        "provider", result.provider(),
        "trace",
            List.of(
                "Loaded current version and " + history.size() + " historical requirements",
                "Applied " + changeType + " request",
                "Generated a new immutable version"),
        "regression", regression(currentHtml, result.html(), changeType));
  }

  /**
   * Agent 第 1 阶段：生成可编辑实施计划，等待人工批准。此时不调用代码生成模型。
   *
   * <p>何时用到：用户点击「生成计划」，{@code POST /api/projects/plan}。
   *
   * <h3>算法</h3>
   *
   * <ol>
   *   <li>与 {@link #generate} 相同的项目创建规则（CREATE / 无 projectId → 新项目，status=PLAN_READY）。
   *   <li>先 UPDATE 项目 prompt，再让前端读项目——否则 UI 会把旧 prompt 还原回来。
   *   <li>新项目不读历史；否则取最近 8 条需求 + 最新 HTML，交给 {@link DeepSeekService#plan}。
   *   <li>INSERT {@code agent_runs}：status=PLAN_READY，stage=AWAITING_APPROVAL，trace 已写两行中文进度。
   *   <li>返回 {@link #run} 整行，前端展示计划文本供编辑。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * POST /api/projects/plan
   * X-User-Id: 1
   * {"prompt":"做一个待办列表","changeType":"CREATE"}
   * // → {id:10, status:"PLAN_READY", stage:"AWAITING_APPROVAL", plan:"1. ...\n2. ...", ...}
   * }</pre>
   *
   * @param uid 当前用户
   * @param body 需求与变更类型
   * @return 新建的 Agent 任务详情
   */
  @PostMapping("/projects/plan")
  public Map<String, Object> plan(
      @RequestHeader("X-User-Id") long uid, @RequestBody Generate body) {
    if (body.prompt() == null || body.prompt().isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "描述不能为空");
    String now = Instant.now().toString();
    String type = body.changeType() == null ? "CREATE" : body.changeType();
    boolean freshProject = "CREATE".equals(type);
    long projectId =
        (freshProject || body.projectId() == null)
            ? insert(
                "INSERT INTO projects(user_id,title,prompt,status,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?)",
                uid,
                title(body.prompt()),
                body.prompt(),
                "PLAN_READY",
                now,
                now)
            : body.projectId();
    owned(uid, projectId);
    // 先持久化本次需求再读项目，否则前端会把旧 prompt 还原回来。
    db.update(
        "UPDATE projects SET prompt=?,status='PLAN_READY',updated_at=? WHERE id=?",
        body.prompt(),
        now,
        projectId);
    List<String> history =
        freshProject
            ? List.of()
            : db.query(
                "SELECT prompt FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 8",
                (rs, n) -> rs.getString(1),
                projectId);
    List<Map<String, Object>> latest =
        freshProject
            ? List.of()
            : db.queryForList(
                "SELECT html FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 1",
                projectId);
    String source = latest.isEmpty() ? "" : latest.getFirst().get("html").toString();
    String plan = deepSeek.plan(body.prompt(), source, history, type);
    long runId =
        insert(
            "INSERT INTO"
                + " agent_runs(project_id,user_id,prompt,change_type,plan,status,stage,trace,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)",
            projectId,
            uid,
            body.prompt(),
            type,
            plan,
            "PLAN_READY",
            "AWAITING_APPROVAL",
            "已读取当前版本与历史需求\n已生成可编辑实施计划",
            now,
            now);
    db.update("UPDATE projects SET status='PLAN_READY',updated_at=? WHERE id=?", now, projectId);
    return run(uid, runId);
  }

  /**
   * Agent 第 2 阶段：批准前允许用户改写实施计划。
   *
   * <p>何时用到：计划编辑框保存，{@code PATCH /api/agent-runs/{id}/plan}。
   *
   * <h3>算法</h3>
   *
   * 状态机：仅 {@code PLAN_READY} 可编辑；RUNNING/COMPLETED 等 → 409。 空计划 → 400。成功则覆盖 {@code plan}，trace
   * 追加「用户已编辑实施计划」。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * PATCH /api/agent-runs/10/plan
   * X-User-Id: 1
   * {"plan":"1. 保留列表\n2. 增加截止日期字段\n3. 生成 HTML\n4. 回归检查"}
   * }</pre>
   *
   * @param uid 当前用户
   * @param id agent_run id
   * @param body 新的计划文本
   * @return 更新后的任务
   */
  @PatchMapping("/agent-runs/{id}/plan")
  public Map<String, Object> editPlan(
      @RequestHeader("X-User-Id") long uid,
      @PathVariable("id") long id,
      @RequestBody PlanEdit body) {
    Map<String, Object> r = ownedRun(uid, id);
    if (!"PLAN_READY".equals(r.get("status")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "计划已开始执行，不能编辑");
    if (body.plan() == null || body.plan().isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划不能为空");
    db.update(
        "UPDATE agent_runs SET plan=?,trace=?,updated_at=? WHERE id=?",
        body.plan(),
        r.get("trace") + "\n用户已编辑实施计划",
        Instant.now().toString(),
        id);
    return run(uid, id);
  }

  /**
   * Agent 第 3 阶段：批准执行；本请求立即返回，生成在后台跑。
   *
   * <p>何时用到：用户点击「批准并执行」，{@code POST /api/agent-runs/{id}/approve}。
   *
   * <h3>算法</h3>
   *
   * <ol>
   *   <li>必须仍是 PLAN_READY，否则 409（防止重复批准）。
   *   <li>同步写库：status=RUNNING，stage=LOADING_CONTEXT，trace 追加「已批准 / 正在加载上下文」。
   *   <li>{@code CompletableFuture.runAsync(() -> executeRun(id))}：用 ForkJoin 公共池异步执行，当前 HTTP
   *       线程立刻返回 RUNNING 快照。
   *   <li>前端随后轮询 {@link #agentRun} 看 stage 从 LOADING_CONTEXT → GENERATING → REGRESSION → COMPLETED。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * POST /api/agent-runs/10/approve
   * // 立即 200：{status:"RUNNING", stage:"LOADING_CONTEXT", ...}
   * // 然后每秒 GET /api/agent-runs/10 直到 COMPLETED / FAILED / CANCELLED
   * }</pre>
   *
   * @param uid 当前用户
   * @param id agent_run id
   * @return 刚进入 RUNNING 的任务快照
   */
  @PostMapping("/agent-runs/{id}/approve")
  public Map<String, Object> approve(
      @RequestHeader("X-User-Id") long uid, @PathVariable("id") long id) {
    Map<String, Object> r = ownedRun(uid, id);
    if (!"PLAN_READY".equals(r.get("status")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务不可批准");
    db.update(
        "UPDATE agent_runs SET status='RUNNING',stage='LOADING_CONTEXT',trace=?,updated_at=? WHERE"
            + " id=?",
        r.get("trace") + "\n用户已批准执行\n正在加载代码上下文",
        Instant.now().toString(),
        id);
    CompletableFuture.runAsync(() -> executeRun(id));
    return run(uid, id);
  }

  /**
   * 取消 Agent 任务。
   *
   * <p>何时用到：用户点击取消，{@code POST /api/agent-runs/{id}/cancel}。
   *
   * <h3>取消语义（协作式，不是杀线程）</h3>
   *
   * <ol>
   *   <li>已是终态 COMPLETED / FAILED / CANCELLED → 原样返回，幂等。
   *   <li>否则立刻把 run 标 CANCELLED，项目 status 拉回 READY。
   *   <li>若 {@link #executeRun} 还在调 DeepSeek：HTTP 无法强行中断；生成结束后会再 SELECT status， 发现 CANCELLED 则 <b>不
   *       INSERT generation</b>，等于丢弃这次模型结果。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * POST /api/agent-runs/10/cancel
   * // {status:"CANCELLED", stage:"CANCELLED", trace:"...\n用户已取消任务"}
   * }</pre>
   *
   * @param uid 当前用户
   * @param id agent_run id
   * @return 取消后（或已是终态）的任务
   */
  @PostMapping("/agent-runs/{id}/cancel")
  public Map<String, Object> cancel(
      @RequestHeader("X-User-Id") long uid, @PathVariable("id") long id) {
    Map<String, Object> r = ownedRun(uid, id);
    if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(r.get("status"))) return r;
    db.update(
        "UPDATE agent_runs SET status='CANCELLED',stage='CANCELLED',trace=?,updated_at=? WHERE"
            + " id=?",
        r.get("trace") + "\n用户已取消任务",
        Instant.now().toString(),
        id);
    db.update(
        "UPDATE projects SET status='READY',updated_at=? WHERE id=?",
        Instant.now().toString(),
        r.get("project_id"));
    return run(uid, id);
  }

  /**
   * 查询单次 Agent 运行的当前状态。
   *
   * <p>做什么：按用户归属读取 agent_runs 一行。 何时用到：批准后前端轮询进度，{@code GET /api/agent-runs/{id}}。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET /api/agent-runs/10
   * // {status:"RUNNING", stage:"GENERATING", trace:"...\n正在调用 DeepSeek 生成代码"}
   * }</pre>
   *
   * @param uid 当前用户
   * @param id agent_run id
   * @return 任务详情
   */
  @GetMapping("/agent-runs/{id}")
  public Map<String, Object> agentRun(
      @RequestHeader("X-User-Id") long uid, @PathVariable("id") long id) {
    return run(uid, id);
  }

  /**
   * 删除项目及其全部生成版本。
   *
   * <p>做什么：校验归属后先删 generations，再删 projects（避免外键/孤儿行）。 何时用到：列表中删除项目，{@code DELETE
   * /api/projects/{id}}。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * DELETE /api/projects/3
   * X-User-Id: 1
   * // 204/空 body；再 GET /api/projects/3 → 404
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   */
  @DeleteMapping("/projects/{id}")
  public void delete(@RequestHeader("X-User-Id") long uid, @PathVariable("id") long id) {
    owned(uid, id);
    db.update("DELETE FROM generations WHERE project_id=?", id);
    db.update("DELETE FROM projects WHERE id=?", id);
  }

  /**
   * 按 id 读取对外可展示的用户字段（不含密码哈希）。
   *
   * <p>何时用到：注册/登录成功后组装响应。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * user(1) → {id=1, name="Owen", email="owen@example.com"}
   * }</pre>
   *
   * @param id 用户 id
   * @return 用户公开资料
   */
  private Map<String, Object> user(long id) {
    return db.queryForMap("SELECT id,name,email FROM users WHERE id=?", id);
  }

  /**
   * 后台实际执行一次已批准的 Agent 任务。无 HTTP 入口，只由 {@link #approve} 异步调用。
   *
   * <h3>状态机与算法</h3>
   *
   * <pre>
   * 读 agent_runs
   *   └─ 已 CANCELLED？ → return（批准后、执行前被取消）
   * 加载最近 8 条 history + 最新 HTML
   * appendTrace(GENERATING) → deepSeek.generate(...)
   *   └─ 再次读 status，若 CANCELLED → return（不落新版本）
   * appendTrace(REGRESSION)
   * version = MAX(version)+1，INSERT generations（不可变新行）
   * regression(旧HTML, 新HTML, changeType)
   * appendTrace(COMPLETED) → run=COMPLETED，project=READY
   * 任意异常 → status=FAILED，trace 追加「执行失败：{message}」
   * </pre>
   *
   * 前端轮询看到的 stage 顺序：LOADING_CONTEXT（approve 已写）→ GENERATING → REGRESSION → COMPLETED。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * // 不要直接调。approve 内部：
   * CompletableFuture.runAsync(() -> executeRun(10L));
   * // 数秒后 GET /api/agent-runs/10 → status=COMPLETED, result_version=2
   * }</pre>
   *
   * @param runId {@code agent_runs.id}
   */
  private void executeRun(long runId) {
    try {
      Map<String, Object> r = db.queryForMap("SELECT * FROM agent_runs WHERE id=?", runId);
      if ("CANCELLED".equals(r.get("status"))) return;
      long projectId = ((Number) r.get("project_id")).longValue();
      List<String> history =
          db.query(
              "SELECT prompt FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 8",
              (rs, n) -> rs.getString(1),
              projectId);
      List<Map<String, Object>> old =
          db.queryForList(
              "SELECT html FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 1",
              projectId);
      String previous = old.isEmpty() ? "" : old.getFirst().get("html").toString();
      appendTrace(runId, "正在调用 DeepSeek 生成代码", "GENERATING");
      DeepSeekService.Result result =
          deepSeek.generate(
              r.get("prompt").toString(), previous, history, r.get("change_type").toString());
      if ("CANCELLED"
          .equals(
              db.queryForObject("SELECT status FROM agent_runs WHERE id=?", String.class, runId)))
        return;
      appendTrace(runId, "正在进行结构回归检查", "REGRESSION");
      Integer last =
          db.queryForObject(
              "SELECT COALESCE(MAX(version),0) FROM generations WHERE project_id=?",
              Integer.class,
              projectId);
      int version = (last == null ? 0 : last) + 1;
      insert(
          "INSERT INTO generations(project_id,version,prompt,html,provider,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          projectId,
          version,
          r.get("prompt"),
          result.html(),
          result.provider(),
          Instant.now().toString());
      Map<String, Object> report =
          regression(previous, result.html(), r.get("change_type").toString());
      String reportText = report.get("status") + ": " + report.get("summary");
      appendTrace(runId, "已保存版本 v" + version + "；回归检查完成", "COMPLETED");
      db.update(
          "UPDATE agent_runs SET"
              + " status='COMPLETED',stage='COMPLETED',result_version=?,regression=?,updated_at=?"
              + " WHERE id=?",
          version,
          reportText,
          Instant.now().toString(),
          runId);
      db.update(
          "UPDATE projects SET status='READY',prompt=?,updated_at=? WHERE id=?",
          r.get("prompt"),
          Instant.now().toString(),
          projectId);
    } catch (Exception e) {
      db.update(
          "UPDATE agent_runs SET status='FAILED',stage='FAILED',trace=trace || ?,updated_at=? WHERE"
              + " id=?",
          "\n执行失败：" + e.getMessage(),
          Instant.now().toString(),
          runId);
    }
  }

  /**
   * 向 Agent 任务追加一行可观察日志，并更新当前阶段。
   *
   * <p>算法：SQL {@code trace = trace || ('\n' + message)} 做字符串拼接；同时覆盖 stage 与 updated_at。 前端轮询 {@link
   * #agentRun} 即可看到新进度，无需 WebSocket。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * appendTrace(10, "正在调用 DeepSeek 生成代码", "GENERATING");
   * // DB: stage=GENERATING, trace 末尾多一行上述中文
   * }</pre>
   *
   * @param id agent_run id
   * @param message 追加到 trace 的中文说明
   * @param stage 如 {@code GENERATING} / {@code REGRESSION} / {@code COMPLETED}
   */
  private void appendTrace(long id, String message, String stage) {
    db.update(
        "UPDATE agent_runs SET stage=?,trace=trace || ?,updated_at=? WHERE id=?",
        stage,
        "\n" + message,
        Instant.now().toString(),
        id);
  }

  /**
   * 按用户读取一条 Agent 任务；不存在或不属于该用户则 404（不泄露他人任务是否存在）。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * run(1, 10)  // 用户 1 的任务 10
   * run(2, 10)  // 任务属于别人 → 404 任务不存在
   * }</pre>
   *
   * @param uid 当前用户
   * @param id agent_run id
   * @return 任务行
   */
  private Map<String, Object> run(long uid, long id) {
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT"
                + " id,project_id,prompt,change_type,plan,status,stage,trace,regression,result_version,created_at,updated_at"
                + " FROM agent_runs WHERE id=? AND user_id=?",
            id,
            uid);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
    return rows.getFirst();
  }

  /**
   * 确认 Agent 任务属于当前用户。写操作入口的别名，语义上强调「先鉴权再改」。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * Map r = ownedRun(uid, runId); // 等同 run(uid, runId)
   * }</pre>
   *
   * @param uid 当前用户
   * @param id agent_run id
   * @return 任务行
   */
  private Map<String, Object> ownedRun(long uid, long id) {
    return run(uid, id);
  }

  /**
   * 取出指定（或最新）生成产物。先 {@link #owned} 再查 generations。
   *
   * <p>SQL 分支与 {@link #preview} 相同：无 version 取 MAX，有则精确匹配。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * artifact(1, 3, null)  // 项目 3 最新一版
   * artifact(1, 3, 2)     // 项目 3 的 v2
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   * @param version 空则最新版
   * @return 单条 generation（html / provider / version）
   */
  private Map<String, Object> artifact(long uid, long id, Integer version) {
    owned(uid, id);
    String sql =
        "SELECT html,provider,version FROM generations WHERE project_id=? "
            + (version == null ? "ORDER BY version DESC LIMIT 1" : "AND version=?");
    List<Map<String, Object>> found =
        version == null ? db.queryForList(sql, id) : db.queryForList(sql, id, version);
    if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无生成结果");
    return found.getFirst();
  }

  /**
   * 结构回归信号：比较新旧 HTML 中按钮、表单、脚本的出现次数。
   *
   * <p>这不是浏览器测试，只是给评审看的透明结构提示。
   *
   * <h3>算法</h3>
   *
   * <ol>
   *   <li>before 为空（首版）→ {@code BASELINE}，不比较。
   *   <li>否则用 {@link #count} 分别统计 {@code <button}、{@code <form}、{@code <script}（故意不含 {@code >}， 这样
   *       {@code <button class="x">} 也能命中）。
   *   <li>判定：三类计数都 {@code 新 ≥ 旧} → PASS；任一减少 → WARN（可能删了核心控件，需人工看预览）。 增加控件算通过；本检查不识别「换成别的标签但功能还在」。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * regression("", "<html><button>ok</button></html>", "CREATE");
   * // {status=BASELINE, summary="已创建首个版本..."}
   *
   * regression("<button><form><script>", "<button><script>", "MODIFY");
   * // 表单 1→0 → {status=WARN, summary="MODIFY 结构回归：按钮 1→1，表单 1→0，脚本 1→1。请在预览中人工确认功能。"}
   * }</pre>
   *
   * @param before 旧 HTML
   * @param after 新 HTML
   * @param type 变更类型，写入摘要文案
   * @return {@code status} 与 {@code summary}
   */
  private Map<String, Object> regression(String before, String after, String type) {
    if (before == null || before.isBlank())
      return Map.of("status", "BASELINE", "summary", "已创建首个版本；后续修改将进行结构回归检查。");
    int oldButtons = count(before, "<button"),
        oldForms = count(before, "<form"),
        oldScripts = count(before, "<script");
    int newButtons = count(after, "<button"),
        newForms = count(after, "<form"),
        newScripts = count(after, "<script");
    boolean pass = newButtons >= oldButtons && newForms >= oldForms && newScripts >= oldScripts;
    return Map.of(
        "status",
        pass ? "PASS" : "WARN",
        "summary",
        type
            + " 结构回归：按钮 "
            + oldButtons
            + "→"
            + newButtons
            + "，表单 "
            + oldForms
            + "→"
            + newForms
            + "，脚本 "
            + oldScripts
            + "→"
            + newScripts
            + (pass ? "。未发现核心结构减少。" : "。请在预览中人工确认功能。"));
  }

  /**
   * 统计源码中某标记出现次数（朴素子串扫描，不解析 DOM）。
   *
   * <h3>算法</h3>
   *
   * 从下标 0 反复 {@code indexOf(token, at)}：命中则 {@code total++}，并把 {@code at} 推进 {@code
   * token.length()}， 避免同一位置死循环。重叠子串不会重复计数（例如 token 长度为 n，下一次从 n 之后开始）。 复杂度 O(|source| × 匹配次数)，HTML
   * 规模下足够。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * count("<button></button><button>", "<button")  // → 2
   * count("<BUTTON>", "<button")                   // → 0（区分大小写）
   * }</pre>
   *
   * @param source 待扫描文本
   * @param token 子串
   * @return 出现次数
   */
  private int count(String source, String token) {
    int at = 0, total = 0;
    while ((at = source.indexOf(token, at)) >= 0) {
      total++;
      at += token.length();
    }
    return total;
  }

  /**
   * 读取属于当前用户的项目，否则 404。
   *
   * <p>算法：{@code WHERE id=? AND user_id=?}。别人的项目与不存在的项目都返回「项目不存在」，避免探测 id。 返回 {@code new
   * LinkedHashMap} 副本，调用方才能再 {@code put} generations 等字段。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * owned(1, 3)  // 用户 1 拥有项目 3 → 项目 Map
   * owned(2, 3)  // 404
   * }</pre>
   *
   * @param uid 当前用户
   * @param id 项目 id
   * @return 可修改的项目 Map
   */
  private Map<String, Object> owned(long uid, long id) {
    List<Map<String, Object>> x =
        db.queryForList(
            "SELECT id,title,prompt,status,created_at,updated_at FROM projects WHERE id=? AND"
                + " user_id=?",
            id,
            uid);
    if (x.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    return new LinkedHashMap<>(x.getFirst());
  }

  /**
   * 执行 INSERT 并返回数据库生成的主键。
   *
   * <h3>算法</h3>
   *
   * 用 {@link GeneratedKeyHolder}：{@code prepareStatement(sql, new String[]{"id"})} 声明要回填的列； 按
   * 1-based 下标 {@code setObject} 绑定 args；{@code h.getKey().longValue()} 即 SQLite 自增 id。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * long userId = insert("INSERT INTO users(name,email,password_hash,created_at) VALUES(?,?,?,?)",
   *     "Owen", "a@b.com", hash("pw"), Instant.now().toString());
   * }</pre>
   *
   * @param sql 带 {@code ?} 的 INSERT
   * @param args 绑定参数
   * @return 新主键
   */
  private long insert(String sql, Object... args) {
    KeyHolder h = new GeneratedKeyHolder();
    db.update(
        c -> {
          var s = c.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          return s;
        },
        h);
    return Objects.requireNonNull(h.getKey()).longValue();
  }

  /**
   * 用需求文本截出项目标题。
   *
   * <p>算法：{@code length > 32} 则 {@code substring(0, 32) + "…"}，否则原样。按 UTF-16 code unit 截断，
   * 代理对（emoji）可能在中间切开，演示场景可接受。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * title("短标题")                         // "短标题"
   * title("这是一段超过三十二个字符的产品需求描述……") // 前 32 字 + "…"
   * }</pre>
   *
   * @param prompt 完整需求
   * @return 短标题
   */
  private String title(String prompt) {
    return prompt.length() > 32 ? prompt.substring(0, 32) + "…" : prompt;
  }

  /**
   * 演示用密码哈希：SHA-256 → 小写十六进制，无盐。
   *
   * <p>算法：UTF-8 字节 → {@code MessageDigest.getInstance("SHA-256").digest} → {@code
   * HexFormat.formatHex}。 同一明文永远得到同一摘要，故可直接 SQL 等值比对。生产应改用 Argon2/bcrypt（带盐、可调 cost）和真实会话。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * hash("secret1")
   * // → "5e5b5c5d..."（64 位 hex）
   * // 注册写入该值；登录时 hash(输入) 与库中值比较
   * }</pre>
   *
   * @param value 明文密码
   * @return hex 摘要
   */
  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 注册/登录请求体。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * new Auth("Owen", "owen@example.com", "secret1")
   * }</pre>
   *
   * @param name 显示名，可空，空则用 {@code Builder}
   * @param email 登录邮箱
   * @param password 明文密码
   */
  public record Auth(String name, String email, String password) {}

  /**
   * 生成/规划请求体。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * new Generate(null, "做一个番茄钟", "CREATE")
   * new Generate(3L, "加暂停按钮", "MODIFY")
   * }</pre>
   *
   * @param projectId 已有项目 id；CREATE 或为空时新建
   * @param prompt 自然语言需求
   * @param changeType {@code CREATE} / {@code MODIFY} / {@code BUGFIX}
   */
  public record Generate(Long projectId, String prompt, String changeType) {}

  /**
   * 编辑实施计划的请求体。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * new PlanEdit("1. 保留表单\n2. 按钮改绿色\n3. 回归检查")
   * }</pre>
   *
   * @param plan 用户改写后的计划全文
   */
  public record PlanEdit(String plan) {}
}
