package dev.atomsdemo.api;

import dev.atomsdemo.service.DeepSeekService;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CompletableFuture;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** REST API for identity, project history and application generation. */
@RestController
@RequestMapping("/api")
public class ApiController {
  private final JdbcTemplate db; private final DeepSeekService deepSeek;
  public ApiController(JdbcTemplate db, DeepSeekService deepSeek) { this.db = db; this.deepSeek = deepSeek; }

  /** Demo authentication uses a signed-in user id stored locally by the browser. */
  @PostMapping("/auth/register") public Map<String,Object> register(@RequestBody Auth body) {
    if (body.email() == null || !body.email().contains("@") || body.password() == null || body.password().length() < 6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效邮箱和至少 6 位密码");
    try { long id = insert("INSERT INTO users(name,email,password_hash,created_at) VALUES(?,?,?,?)", body.name() == null || body.name().isBlank() ? "Builder" : body.name(), body.email().toLowerCase(), hash(body.password()), Instant.now().toString()); return user(id); }
    catch (Exception e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已注册"); }
  }
  @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Auth body) {
    List<Long> users = db.query("SELECT id FROM users WHERE email=? AND password_hash=?", (rs,n)->rs.getLong(1), body.email().toLowerCase(), hash(body.password()));
    if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"); return user(users.getFirst());
  }
  @GetMapping("/projects") public List<Map<String,Object>> projects(@RequestHeader("X-User-Id") long uid) {
    return db.queryForList("SELECT p.id,p.title,p.prompt,p.status,p.created_at,p.updated_at,COUNT(g.id) AS versions FROM projects p LEFT JOIN generations g ON g.project_id=p.id WHERE p.user_id=? GROUP BY p.id ORDER BY p.updated_at DESC", uid);
  }
  @GetMapping("/projects/{id}") public Map<String,Object> project(@RequestHeader("X-User-Id") long uid, @PathVariable("id") long id) {
    Map<String,Object> p = owned(uid,id); p.put("generations", db.queryForList("SELECT id,version,prompt,provider,created_at FROM generations WHERE project_id=? ORDER BY version DESC", id));
    p.put("agentRuns", db.queryForList("SELECT id,change_type,prompt,status,stage,plan,trace,regression,result_version,created_at,updated_at FROM agent_runs WHERE project_id=? ORDER BY id DESC", id));
    return p;
  }
  @GetMapping("/projects/{id}/preview") public Map<String,Object> preview(@RequestHeader("X-User-Id") long uid, @PathVariable("id") long id, @RequestParam(required=false) Integer version) {
    owned(uid,id); String sql = "SELECT html,provider,version FROM generations WHERE project_id=? " + (version == null ? "ORDER BY version DESC LIMIT 1" : "AND version=?");
    List<Map<String,Object>> result = version == null ? db.queryForList(sql,id) : db.queryForList(sql,id,version); if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无生成结果"); return result.getFirst();
  }
  /** Returns source as text so builders can audit a generated version. */
  @GetMapping(value="/projects/{id}/code", produces=MediaType.TEXT_PLAIN_VALUE) public String code(@RequestHeader("X-User-Id") long uid, @PathVariable("id") long id, @RequestParam(required=false) Integer version) {
    return artifact(uid, id, version).get("html").toString();
  }
  /** Exports one executable artifact and its concise provenance without exposing server secrets. */
  @GetMapping("/projects/{id}/export") public ResponseEntity<byte[]> export(@RequestHeader("X-User-Id") long uid, @PathVariable("id") long id, @RequestParam(required=false) Integer version) {
    Map<String,Object> item=artifact(uid,id,version); int v=((Number)item.get("version")).intValue();
    try (ByteArrayOutputStream out=new ByteArrayOutputStream(); ZipOutputStream zip=new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("index.html")); zip.write(item.get("html").toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
      zip.putNextEntry(new ZipEntry("README.md")); zip.write(("# Generated application\n\nProject ID: "+id+"\nVersion: "+v+"\nProvider: "+item.get("provider")+"\n").getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); zip.finish();
      return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=atoms-project-"+id+"-v"+v+".zip").contentType(MediaType.APPLICATION_OCTET_STREAM).body(out.toByteArray());
    } catch(Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"导出失败"); }
  }
  @PostMapping("/projects/generate") public Map<String,Object> generate(@RequestHeader("X-User-Id") long uid, @RequestBody Generate body) {
    if (body.prompt()==null || body.prompt().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"描述不能为空");
    String now=Instant.now().toString(); String requestedType=body.changeType()==null?"CREATE":body.changeType(); boolean freshProject="CREATE".equals(requestedType); long projectId = (freshProject||body.projectId()==null) ? insert("INSERT INTO projects(user_id,title,prompt,status,created_at,updated_at) VALUES(?,?,?,?,?,?)",uid,title(body.prompt()),body.prompt(),"GENERATING",now,now) : body.projectId(); owned(uid,projectId);
    db.update("UPDATE projects SET status='GENERATING',prompt=?,updated_at=? WHERE id=?", body.prompt(),now,projectId);
    List<String> history=freshProject?List.of():db.query("SELECT prompt FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 8",(rs,n)->rs.getString(1),projectId);
    List<Map<String,Object>> previous=freshProject?List.of():db.queryForList("SELECT html FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 1",projectId);
    String currentHtml=previous.isEmpty()?"":previous.getFirst().get("html").toString(); String changeType=requestedType;
    DeepSeekService.Result result=deepSeek.generate(body.prompt(),currentHtml,history,changeType);
    Integer last=db.queryForObject("SELECT COALESCE(MAX(version),0) FROM generations WHERE project_id=?",Integer.class,projectId);
    int v=(last==null?0:last)+1; insert("INSERT INTO generations(project_id,version,prompt,html,provider,created_at) VALUES(?,?,?,?,?,?)",projectId,v,body.prompt(),result.html(),result.provider(),Instant.now().toString());
    db.update("UPDATE projects SET status='READY',updated_at=? WHERE id=?",Instant.now().toString(),projectId);
    return Map.of("projectId",projectId,"version",v,"provider",result.provider(),"trace",List.of("Loaded current version and "+history.size()+" historical requirements","Applied "+changeType+" request","Generated a new immutable version"),"regression",regression(currentHtml,result.html(),changeType));
  }
  /** Stage 1: persist a model-generated plan and wait for explicit human approval. */
  @PostMapping("/projects/plan") public Map<String,Object> plan(@RequestHeader("X-User-Id") long uid,@RequestBody Generate body) {
    if(body.prompt()==null||body.prompt().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"描述不能为空"); String now=Instant.now().toString(); String type=body.changeType()==null?"CREATE":body.changeType(); boolean freshProject="CREATE".equals(type);
    long projectId=(freshProject||body.projectId()==null)?insert("INSERT INTO projects(user_id,title,prompt,status,created_at,updated_at) VALUES(?,?,?,?,?,?)",uid,title(body.prompt()),body.prompt(),"PLAN_READY",now,now):body.projectId(); owned(uid,projectId);
    // Persist the pending request before reloading the project; otherwise the UI restores an older prompt.
    db.update("UPDATE projects SET prompt=?,status='PLAN_READY',updated_at=? WHERE id=?",body.prompt(),now,projectId);
    List<String> history=freshProject?List.of():db.query("SELECT prompt FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 8",(rs,n)->rs.getString(1),projectId); List<Map<String,Object>> latest=freshProject?List.of():db.queryForList("SELECT html FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 1",projectId); String source=latest.isEmpty()?"":latest.getFirst().get("html").toString();
    String plan=deepSeek.plan(body.prompt(),source,history,type); long runId=insert("INSERT INTO agent_runs(project_id,user_id,prompt,change_type,plan,status,stage,trace,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",projectId,uid,body.prompt(),type,plan,"PLAN_READY","AWAITING_APPROVAL","已读取当前版本与历史需求\n已生成可编辑实施计划",now,now);
    db.update("UPDATE projects SET status='PLAN_READY',updated_at=? WHERE id=?",now,projectId); return run(uid,runId);
  }
  /** Stage 2: the user can edit the plan before approving execution. */
  @PatchMapping("/agent-runs/{id}/plan") public Map<String,Object> editPlan(@RequestHeader("X-User-Id") long uid,@PathVariable("id") long id,@RequestBody PlanEdit body) { Map<String,Object> r=ownedRun(uid,id); if(!"PLAN_READY".equals(r.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"计划已开始执行，不能编辑"); if(body.plan()==null||body.plan().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"计划不能为空"); db.update("UPDATE agent_runs SET plan=?,trace=?,updated_at=? WHERE id=?",body.plan(),r.get("trace")+"\n用户已编辑实施计划",Instant.now().toString(),id); return run(uid,id); }
  /** Stage 3: starts a non-blocking run; the browser observes it by polling GET /agent-runs/{id}. */
  @PostMapping("/agent-runs/{id}/approve") public Map<String,Object> approve(@RequestHeader("X-User-Id") long uid,@PathVariable("id") long id) { Map<String,Object> r=ownedRun(uid,id); if(!"PLAN_READY".equals(r.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"该任务不可批准"); db.update("UPDATE agent_runs SET status='RUNNING',stage='LOADING_CONTEXT',trace=?,updated_at=? WHERE id=?",r.get("trace")+"\n用户已批准执行\n正在加载代码上下文",Instant.now().toString(),id); CompletableFuture.runAsync(()->executeRun(id)); return run(uid,id); }
  /** Cancellation is immediate before execution and cooperative while a model request is in flight. */
  @PostMapping("/agent-runs/{id}/cancel") public Map<String,Object> cancel(@RequestHeader("X-User-Id") long uid,@PathVariable("id") long id) { Map<String,Object> r=ownedRun(uid,id); if(Set.of("COMPLETED","FAILED","CANCELLED").contains(r.get("status")))return r; db.update("UPDATE agent_runs SET status='CANCELLED',stage='CANCELLED',trace=?,updated_at=? WHERE id=?",r.get("trace")+"\n用户已取消任务",Instant.now().toString(),id); db.update("UPDATE projects SET status='READY',updated_at=? WHERE id=?",Instant.now().toString(),r.get("project_id")); return run(uid,id); }
  @GetMapping("/agent-runs/{id}") public Map<String,Object> agentRun(@RequestHeader("X-User-Id") long uid,@PathVariable("id") long id) { return run(uid,id); }
  @DeleteMapping("/projects/{id}") public void delete(@RequestHeader("X-User-Id") long uid,@PathVariable("id") long id) { owned(uid,id); db.update("DELETE FROM generations WHERE project_id=?",id); db.update("DELETE FROM projects WHERE id=?",id); }
  private Map<String,Object> user(long id) { return db.queryForMap("SELECT id,name,email FROM users WHERE id=?",id); }
  private void executeRun(long runId) { try { Map<String,Object> r=db.queryForMap("SELECT * FROM agent_runs WHERE id=?",runId); if("CANCELLED".equals(r.get("status")))return; long projectId=((Number)r.get("project_id")).longValue(); List<String> history=db.query("SELECT prompt FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 8",(rs,n)->rs.getString(1),projectId); List<Map<String,Object>> old=db.queryForList("SELECT html FROM generations WHERE project_id=? ORDER BY version DESC LIMIT 1",projectId); String previous=old.isEmpty()?"":old.getFirst().get("html").toString(); appendTrace(runId,"正在调用 DeepSeek 生成代码", "GENERATING"); DeepSeekService.Result result=deepSeek.generate(r.get("prompt").toString(),previous,history,r.get("change_type").toString()); if("CANCELLED".equals(db.queryForObject("SELECT status FROM agent_runs WHERE id=?",String.class,runId)))return; appendTrace(runId,"正在进行结构回归检查", "REGRESSION"); Integer last=db.queryForObject("SELECT COALESCE(MAX(version),0) FROM generations WHERE project_id=?",Integer.class,projectId); int version=(last==null?0:last)+1; insert("INSERT INTO generations(project_id,version,prompt,html,provider,created_at) VALUES(?,?,?,?,?,?)",projectId,version,r.get("prompt"),result.html(),result.provider(),Instant.now().toString()); Map<String,Object> report=regression(previous,result.html(),r.get("change_type").toString()); String reportText=report.get("status")+": "+report.get("summary"); appendTrace(runId,"已保存版本 v"+version+"；回归检查完成", "COMPLETED"); db.update("UPDATE agent_runs SET status='COMPLETED',stage='COMPLETED',result_version=?,regression=?,updated_at=? WHERE id=?",version,reportText,Instant.now().toString(),runId); db.update("UPDATE projects SET status='READY',prompt=?,updated_at=? WHERE id=?",r.get("prompt"),Instant.now().toString(),projectId); } catch(Exception e) { db.update("UPDATE agent_runs SET status='FAILED',stage='FAILED',trace=trace || ?,updated_at=? WHERE id=?","\n执行失败："+e.getMessage(),Instant.now().toString(),runId); } }
  private void appendTrace(long id,String message,String stage) { db.update("UPDATE agent_runs SET stage=?,trace=trace || ?,updated_at=? WHERE id=?",stage,"\n"+message,Instant.now().toString(),id); }
  private Map<String,Object> run(long uid,long id) { List<Map<String,Object>> rows=db.queryForList("SELECT id,project_id,prompt,change_type,plan,status,stage,trace,regression,result_version,created_at,updated_at FROM agent_runs WHERE id=? AND user_id=?",id,uid); if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"任务不存在"); return rows.getFirst(); }
  private Map<String,Object> ownedRun(long uid,long id) { return run(uid,id); }
  private Map<String,Object> artifact(long uid,long id,Integer version) { owned(uid,id); String sql="SELECT html,provider,version FROM generations WHERE project_id=? "+(version==null?"ORDER BY version DESC LIMIT 1":"AND version=?"); List<Map<String,Object>> found=version==null?db.queryForList(sql,id):db.queryForList(sql,id,version); if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"尚无生成结果"); return found.getFirst(); }
  /** A transparent structural regression signal, not a claim of exhaustive browser testing. */
  private Map<String,Object> regression(String before,String after,String type) { if(before==null||before.isBlank())return Map.of("status","BASELINE","summary","已创建首个版本；后续修改将进行结构回归检查。"); int oldButtons=count(before,"<button"),oldForms=count(before,"<form"),oldScripts=count(before,"<script"),newButtons=count(after,"<button"),newForms=count(after,"<form"),newScripts=count(after,"<script"); boolean pass=newButtons>=oldButtons&&newForms>=oldForms&&newScripts>=oldScripts; return Map.of("status",pass?"PASS":"WARN","summary",type+" 结构回归：按钮 "+oldButtons+"→"+newButtons+"，表单 "+oldForms+"→"+newForms+"，脚本 "+oldScripts+"→"+newScripts+(pass?"。未发现核心结构减少。":"。请在预览中人工确认功能。")); }
  private int count(String source,String token) { int at=0,total=0; while((at=source.indexOf(token,at))>=0){total++;at+=token.length();} return total; }
  private Map<String,Object> owned(long uid,long id) { List<Map<String,Object>> x=db.queryForList("SELECT id,title,prompt,status,created_at,updated_at FROM projects WHERE id=? AND user_id=?",id,uid); if(x.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"项目不存在");return new LinkedHashMap<>(x.getFirst()); }
  private long insert(String sql,Object... args) { KeyHolder h=new GeneratedKeyHolder(); db.update(c->{var s=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},h); return Objects.requireNonNull(h.getKey()).longValue(); }
  private String title(String prompt) { return prompt.length()>32?prompt.substring(0,32)+"…":prompt; }
  /** Sufficient for a take-home demo; production should use Argon2/bcrypt and real sessions. */
  private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
  public record Auth(String name,String email,String password) {} public record Generate(Long projectId,String prompt,String changeType) {} public record PlanEdit(String plan) {}
}
