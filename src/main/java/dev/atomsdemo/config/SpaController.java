package dev.atomsdemo.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 单页应用（SPA）根路径转发。
 *
 * <p>Docker / 生产构建后，Vue 的 {@code index.html} 由 Spring 静态资源托管。 访问站点根 URL
 * 时需要把请求转到该入口文件，否则浏览器拿不到前端壳页面。
 */
@Controller
public class SpaController {

  /**
   * 将站点根路径转发到 Vue 生产构建的入口页。
   *
   * <p>做什么：对 {@code GET /} 做内部 forward，返回 {@code /index.html}，不改变浏览器地址栏。 何时用到：用户打开 {@code
   * http://localhost:8080/} 或容器部署后的根地址时。 {@code /api} 仍由 {@link dev.atomsdemo.api.ApiController}
   * 处理，不会走到这里。
   *
   * <p>转发逻辑：{@code forward:} 是服务端内部转发，不是 302 重定向。 浏览器地址仍是 {@code /}，响应体是打包后的 Vue {@code
   * index.html}。
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * GET http://localhost:8080/
   * // 内部转发到 /index.html，浏览器看到 Vue 工作台
   *
   * GET http://localhost:8080/api/projects
   * // 不会进入本方法，由 ApiController 处理
   * }</pre>
   *
   * @return 转发视图名 {@code forward:/index.html}
   */
  @GetMapping("/")
  public String home() {
    return "forward:/index.html";
  }
}
