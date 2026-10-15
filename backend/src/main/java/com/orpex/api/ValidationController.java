package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.validation.ValidationService;
import com.orpex.validation.ValidationService.Decision;
import com.orpex.validation.ValidationService.Pending;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validation du jour de l'utilisateur connecté (spécification §7). */
@RestController
@RequestMapping("/api/validation")
public class ValidationController {

    /** {@code validate} : envois qui partiront ; {@code reject} : envois décochés, qui ne partiront pas. */
    public record DecisionRequest(List<Long> validate, List<Long> reject) {
    }

    private final ValidationService validation;
    private final CurrentUser currentUser;

    public ValidationController(ValidationService validation, CurrentUser currentUser) {
        this.validation = validation;
        this.currentUser = currentUser;
    }

    @GetMapping("/today")
    public List<Pending> today(Authentication authentication) {
        return validation.today(currentUser.id(authentication));
    }

    @PostMapping
    public Decision decide(@RequestBody DecisionRequest request, Authentication authentication) {
        return validation.decide(currentUser.id(authentication),
                request.validate() == null ? List.of() : request.validate(),
                request.reject() == null ? List.of() : request.reject());
    }
}
