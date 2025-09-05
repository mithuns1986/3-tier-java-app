package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
public class HelloController {
  @Autowired(required = false) JdbcTemplate jdbc;

  @GetMapping("/")
  public String root() {
    return "App tier is alive. Try /api/hello and /db";
  }

  @GetMapping("/api/hello")
  public String hello() {
    String pod = System.getenv().getOrDefault("HOSTNAME", "unknown");
    return "Hello from app pod: " + pod;
  }

  @GetMapping("/db")
  public ResponseEntity<String> db() {
    try {
      if (jdbc == null) return ResponseEntity.ok("No DataSource configured");
      String now = jdbc.queryForObject("SELECT NOW()", String.class);
      return ResponseEntity.ok("DB time: " + now);
    } catch (Exception e) {
      return ResponseEntity.internalServerError().body("DB error: " + e.getMessage());
    }
  }

  @GetMapping("/healthz")
  public ResponseEntity<String> healthz() { return ResponseEntity.ok("ok"); }
}
