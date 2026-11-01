package gov.mot.netmon.user;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.user.UserDtos.CreateUserRequest;
import gov.mot.netmon.user.UserDtos.ResetPasswordRequest;
import gov.mot.netmon.user.UserDtos.RoleResponse;
import gov.mot.netmon.user.UserDtos.UpdateUserRequest;
import gov.mot.netmon.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Users and roles")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @Operation(summary = "List roles")
    public List<RoleResponse> roles() {
        return service.listRoles();
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Search users")
    public PageResponse<UserResponse> search(@RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return service.search(search, active, page, size, sort);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UserResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Create a user")
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return service.create(req);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Update a user's profile and roles")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/users/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UserResponse activate(@PathVariable UUID id) {
        return service.setActive(id, true);
    }

    @PostMapping("/users/{id}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Deactivate a user (revokes all sessions immediately)")
    public UserResponse deactivate(@PathVariable UUID id) {
        return service.setActive(id, false);
    }

    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UserResponse unlock(@PathVariable UUID id) {
        return service.unlock(id);
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Set a new password for a user (revokes all their sessions)")
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.newPassword());
    }
}
