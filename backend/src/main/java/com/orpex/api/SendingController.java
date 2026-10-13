package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.rule.SendingControlService;
import com.orpex.rule.SendingControlService.PauseState;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pause générale des envois : visible de tous (bandeau), décidée par un administrateur ({@code /api/admin/**}).
 */
@RestController
public class SendingController {

    public record GlobalPauseResult(boolean paused, int missed) {
    }

    private final SendingControlService sendingControl;
    private final CurrentUser currentUser;

    public SendingController(SendingControlService sendingControl, CurrentUser currentUser) {
        this.sendingControl = sendingControl;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/sending-status")
    public PauseState status() {
        return sendingControl.globalState();
    }

    @PostMapping("/api/admin/sending/pause")
    public GlobalPauseResult pause(Authentication authentication) {
        return new GlobalPauseResult(true, sendingControl.setGlobalPause(true, currentUser.id(authentication)));
    }

    /** Reprise générale : les envois dont l'heure est passée pendant la pause deviennent manqués. */
    @PostMapping("/api/admin/sending/resume")
    public GlobalPauseResult resume(Authentication authentication) {
        return new GlobalPauseResult(false, sendingControl.setGlobalPause(false, currentUser.id(authentication)));
    }
}
