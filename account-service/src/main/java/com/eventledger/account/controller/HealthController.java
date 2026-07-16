package com.eventledger.account.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Exposes the assignment-required GET /health by forwarding to the Actuator
 * health endpoint, preserving its status code (503 when DOWN) and diagnostics.
 */
@Controller
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "forward:/actuator/health";
    }
}
