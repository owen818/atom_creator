package dev.atomsdemo.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sends the public root URL to Vue's production entry point in container deployments. */
@Controller
public class SpaController {
  @GetMapping("/")
  public String home() {
    return "forward:/index.html";
  }
}
