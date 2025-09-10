package com.example.demo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

  @GetMapping("/")
  public String hello() {
    String pod = System.getenv().getOrDefault("HOSTNAME", "unknown");
    return "Hello from pod: " + pod + " 👋";
  }

  // Used by readiness/liveness probes
  @GetMapping("/healthz")
  public ResponseEntity<String> healthz() {
    return ResponseEntity.ok("ok");
  }
}
