package dev.atomsdemo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 补充配置。
 *
 * <p>开发阶段前端跑在 Vite（默认 {@code http://localhost:5173}），后端跑在 8080， 浏览器会因跨源拦截 {@code /api}
 * 请求。本配置只在开发跨域场景生效。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /**
   * 注册 CORS 规则，允许 Vite 开发服务器调用本机 API。
   *
   * <p>做什么：对 {@code /api/**} 放开来源 {@code http://localhost:5173}，允许任意方法和请求头。 何时用到：前端 {@code npm run
   * dev} 访问 5173 并请求后端时，Spring 在处理跨域预检/实际请求前调用。 生产环境由同一域名托管前端，一般不再依赖这条规则。
   *
   * <p>匹配逻辑：
   *
   * <ol>
   *   <li>仅路径前缀 {@code /api/**} 生效，静态资源 {@code /index.html} 不受影响；
   *   <li>浏览器对跨源 POST/自定义头会先发 OPTIONS 预检，本规则允许任意 method/header；
   *   <li>来源必须精确等于 {@code http://localhost:5173}，其它 Origin 仍被拒绝。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * // 前端跑在 5173，后端 8080 时，浏览器会先发：
   * OPTIONS http://localhost:8080/api/projects
   * Origin: http://localhost:5173
   * // Spring 根据本方法返回 Access-Control-Allow-Origin: http://localhost:5173
   * // 随后真正的 GET/POST 才能通过
   * }</pre>
   *
   * @param registry Spring 提供的 CORS 注册表
   */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins("http://localhost:5173")
        .allowedMethods("*")
        .allowedHeaders("*");
  }
}
