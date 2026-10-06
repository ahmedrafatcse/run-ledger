package com.runledger.controller;

import com.runledger.entity.AppUser;
import com.runledger.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * Development identity stub for browser sessions.
 *
 * <p>The user selects a known demo identity from a dropdown and the server
 * trusts it. There is no password and no verification. This is not an
 * authentication upgrade over the CLI's {@code X-User-Id} header — it's the
 * same kind of stub, shaped for a browser, which can't set custom headers on
 * a normal page request.
 *
 * <p>Production replaces this with real authentication (OAuth, SAML, LDAP)
 * that verifies identity before the session is created. The session
 * mechanism, {@link com.runledger.security.AppSecurityContext}, RLS, and
 * everything downstream are unaffected by that swap — only the code in this
 * class changes.
 */
@Controller
public class LoginController {

    private static final String SESSION_KEY = "userId";

    private final AppUserRepository appUserRepository;

    public LoginController(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/login")
    public String loginForm(Model model) {
        model.addAttribute("users", appUserRepository.findAll());
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam UUID userId, HttpServletRequest request) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown user: " + userId));
        request.getSession(true).setAttribute(SESSION_KEY, user.getId());
        return "redirect:/";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }
}