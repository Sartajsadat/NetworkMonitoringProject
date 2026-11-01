package gov.mot.netmon.auth;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.auth.AuthDtos.SetupAdminRequest;
import gov.mot.netmon.auth.AuthDtos.SetupStatusResponse;
import gov.mot.netmon.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/setup")
@Tag(name = "First-run setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/status")
    @Operation(summary = "Whether the first administrator still needs to be created")
    public SetupStatusResponse status() {
        return new SetupStatusResponse(setupService.isSetupRequired());
    }

    @PostMapping("/admin")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the first SUPER_ADMIN using the one-time setup token")
    public UserResponse createAdmin(@Valid @RequestBody SetupAdminRequest req) {
        return setupService.createFirstAdmin(req);
    }
}
