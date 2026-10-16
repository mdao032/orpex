package com.orpex.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pages de l'interface Angular servie par le JAR : une adresse saisie ou rechargée (ex. /lignes/12) renvoie
 * index.html, et le routeur Angular affiche la bonne page. Liste à tenir alignée sur {@code app.routes.ts}.
 */
@Controller
class SpaForwardController {

    @GetMapping({"/connexion", "/lignes", "/lignes/{id}", "/import", "/validation", "/journal", "/compte",
            "/mot-de-passe", "/admin/{page}"})
    String index() {
        return "forward:/index.html";
    }
}
