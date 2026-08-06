package dev.atomsdemo.api;

import dev.atomsdemo.service.DeepSeekService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
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
    Map<String,Object> p = owned(uid,id); p.put("generations", db.queryForList("SELECT id,version,prompt,provider,created_at FROM generations WHERE project_id=? ORDER BY version DESC", id)); return p;
  }
  @GetMapping("/projects/{id}/preview") public Map<String,Object> preview(@RequestHeader("X-User-Id") long uid, @PathVariable("id") long id, @RequestParam(required=false) Integer version) {
    owned(uid,id); String sql = "SELECT html,provider,version FROM generations WHERE project_id=? " + (version == null ? "ORDER BY version DESC LIMIT 1" : "AND version=?");
    List<Map<String,Object>> result = version == null ? db.queryForList(sql,id) : db.queryForList(sql,id,version); if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无生成结果"); return result.getFirst();
  }
  @PostMapping("/projects/generate") public Map<String,Object> generate(@RequestHeader("X-User-Id") long uid, @RequestBody Generate body) {
    if (body.prompt()==null || body.prompt().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"描述不能为空");
    String now=Instant.now().toString(); long projectId = body.projectId()==null ? insert("INSERT INTO projects(user_id,title,prompt,status,created_at,updated_at) VALUES(?,?,?,?,?,?)",uid,title(body.prompt()),body.prompt(),"GENERATING",now,now) : body.projectId(); owned(uid,projectId);
    db.update("UPDATE projects SET status='GENERATING',prompt=?,updated_at=? WHERE id=?", body.prompt(),now,projectId);
    DeepSeekService.Result result=deepSeek.generate(body.prompt());
    Integer last=db.queryForObject("SELECT COALESCE(MAX(version),0) FROM generations WHERE project_id=?",Integer.class,projectId);
    int v=(last==null?0:last)+1; insert("INSERT INTO generations(project_id,version,prompt,html,provider,created_at) VALUES(?,?,?,?,?,?)",projectId,v,body.prompt(),result.html(),result.provider(),Instant.now().toString());
    db.update("UPDATE projects SET status='READY',updated_at=? WHERE id=?",Instant.now().toString(),projectId);
    return Map.of("projectId",projectId,"version",v,"provider",result.provider());
  }
  @DeleteMapping("/projects/{id}") public void delete(@RequestHeader("X-User-Id") long uid,@PathVariable("id") long id) { owned(uid,id); db.update("DELETE FROM generations WHERE project_id=?",id); db.update("DELETE FROM projects WHERE id=?",id); }
  private Map<String,Object> user(long id) { return db.queryForMap("SELECT id,name,email FROM users WHERE id=?",id); }
  private Map<String,Object> owned(long uid,long id) { List<Map<String,Object>> x=db.queryForList("SELECT id,title,prompt,status,created_at,updated_at FROM projects WHERE id=? AND user_id=?",id,uid); if(x.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"项目不存在");return new LinkedHashMap<>(x.getFirst()); }
  private long insert(String sql,Object... args) { KeyHolder h=new GeneratedKeyHolder(); db.update(c->{var s=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},h); return Objects.requireNonNull(h.getKey()).longValue(); }
  private String title(String prompt) { return prompt.length()>32?prompt.substring(0,32)+"…":prompt; }
  /** Sufficient for a take-home demo; production should use Argon2/bcrypt and real sessions. */
  private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
  public record Auth(String name,String email,String password) {} public record Generate(Long projectId,String prompt) {}
}
