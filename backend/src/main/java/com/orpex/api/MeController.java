package com.orpex.api;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Permet au front de savoir qui est connecté (200) ou s'il doit afficher la page de connexion (401). */
@RestController
@RequestMapping("/api")
public class MeController {

    public record MeResponse(String email, List<String> roles) {
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return new MeResponse(authentication.getName(), roles);
    }
}
