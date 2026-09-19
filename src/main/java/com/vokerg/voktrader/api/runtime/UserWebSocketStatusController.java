package com.vokerg.voktrader.api.runtime;

import com.vokerg.voktrader.polymarket.user.UserWebSocketHealthService;
import com.vokerg.voktrader.polymarket.user.UserWebSocketSafetyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime/user-websocket")
public class UserWebSocketStatusController {
    private final UserWebSocketHealthService healthService;
    private final UserWebSocketSafetyService safetyService;

    public UserWebSocketStatusController(
            UserWebSocketHealthService healthService,
            UserWebSocketSafetyService safetyService
    ) {
        this.healthService = healthService;
        this.safetyService = safetyService;
    }

    @GetMapping
    public UserWebSocketStatus status() {
        return new UserWebSocketStatus(
                healthService.snapshot(),
                safetyService.exposureSnapshot()
        );
    }

    public record UserWebSocketStatus(
            UserWebSocketHealthService.Snapshot health,
            UserWebSocketSafetyService.ExposureSnapshot exposure
    ) {
    }
}
