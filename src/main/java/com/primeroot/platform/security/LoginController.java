package com.primeroot.platform.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {
	@GetMapping({"/", "/login"})
	public String login(@AuthenticationPrincipal OidcUser user) {
		return user == null ? "redirect:/oauth2/authorization/keycloak" : "redirect:/home";
	}
}
