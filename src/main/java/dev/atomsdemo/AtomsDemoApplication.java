package dev.atomsdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 应用入口类。
 *
 * <p>扫描 {@code dev.atomsdemo} 包下的 Controller、Service、Config 并启动内嵌 Web 容器， 对外提供 REST API 和前端静态资源。本地
 * {@code mvn spring-boot:run} 或运行 JAR 时都会进入这里。
 */
@SpringBootApplication
public class AtomsDemoApplication {

  /**
   * JVM 进程入口：启动 Spring 应用上下文。
   *
   * <p>做什么：加载配置（含 {@code DEEPSEEK_API_KEY}）、初始化 SQLite、注册 Bean，然后监听 HTTP 端口。 何时用到：执行 {@code mvn
   * spring-boot:run}、{@code java -jar} 或 Docker 容器启动时由 JVM 自动调用。
   *
   * <p>启动逻辑（顺序）：
   *
   * <ol>
   *   <li>读取 {@code application.properties} / 环境变量（端口、SQLite 路径、DeepSeek 密钥）；
   *   <li>创建 {@code AtomsDemoApplication} 上下文，扫描并实例化 Controller / Service / Config；
   *   <li>按 {@code schema.sql} 初始化数据库表；
   *   <li>内嵌 Tomcat 开始监听（默认 8080，可用 {@code PORT} 覆盖）。
   * </ol>
   *
   * <h3>Usage example</h3>
   *
   * <pre>{@code
   * mvn spring-boot:run
   * # 或
   * DEEPSEEK_API_KEY=sk-xxx java -jar target/atoms-demo-1.0.0.jar
   * # 启动后访问 http://localhost:8080
   * }</pre>
   *
   * @param args 命令行参数，可覆盖 Spring 配置（如 {@code --server.port=9090}）
   */
  public static void main(String[] args) {
    SpringApplication.run(AtomsDemoApplication.class, args);
  }
}
