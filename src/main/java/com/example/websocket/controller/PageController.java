package com.example.websocket.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Maps clean versioned page URLs → HTML files.
 *
 * URL pattern:  /api/v1/<page>
 * Examples:     /api/v1/login   /api/v1/chat   /api/v1/dashboard
 */
@Controller
@RequestMapping("/api/v1")
public class PageController {

    @GetMapping({"", "/"})
    public String index() {
        return "forward:/index.html";
    }

    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    public String register() {
        return "forward:/register.html";
    }

    @GetMapping("/chat")
    public String chat() {
        return "forward:/chat.html";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "forward:/dashboard.html";
    }
}
