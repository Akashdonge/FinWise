package com.finwise.controller;

import com.finwise.dto.UserDTO;
import com.finwise.entity.User;
import com.finwise.exception.UserAlreadyExistsException;
import com.finwise.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public UserController(UserService userService, AuthenticationManager authenticationManager) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        // Initialize SecurityContextRepository for Spring Security 6
        this.securityContextRepository = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository()
        );
    }

    @GetMapping("/auth/user")
    public ResponseEntity<Map<String, Object>> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.ok(Map.of("isAuthenticated", false));
        }

        try {
            String email = null;

            if (authentication instanceof UsernamePasswordAuthenticationToken) {
                email = authentication.getName();
                System.out.println("Form Login Email: " + email);
            } else {
                // Fallback - try to get email from principal
                Object principal = authentication.getPrincipal();
                if (principal instanceof UserDetails) {
                    email = ((UserDetails) principal).getUsername();
                } else {
                    email = principal.toString();
                }
                System.out.println("Auth Email: " + email);
            }

            if (email != null && !email.equals("anonymousUser")) {
                Optional<User> optionalUser = userService.findByEmail(email);

                // FIX: Check if user exists before calling get()
                if (optionalUser.isPresent()) {
                    User user = optionalUser.get();

                    Map<String, Object> response = new HashMap<>();
                    response.put("isAuthenticated", true);

                    // Create user map with null-safe values
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("id", user.getId());
                    userMap.put("username", user.getUsername() != null ? user.getUsername() : "");
                    userMap.put("email", user.getEmail() != null ? user.getEmail() : "");
                    userMap.put("firstName", user.getFirstName() != null ? user.getFirstName() : "");
                    userMap.put("lastName", user.getLastName() != null ? user.getLastName() : "");
                    userMap.put("isNewUser", user.isNewUser());
                    userMap.put("imageUrl", user.getImage_url() != null ? user.getImage_url() : "");
                    userMap.put("role", user.getRole() != null ? user.getRole() : "USER");
                    userMap.put("familyProfileId", user.getProfile() != null ? user.getProfile().getId() : null);

                    response.put("user", userMap);
                    return ResponseEntity.ok(response);
                } else {
                    System.err.println("User not found with email: " + email);
                }
            } else {
                System.err.println("Email is null or anonymousUser");
            }

        } catch (Exception e) {
            System.err.println("Error getting current user: " + e.getMessage());
            e.printStackTrace();
        }

        return ResponseEntity.ok(Map.of("isAuthenticated", false));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
            logoutHandler.logout(request, response, authentication);

            SecurityContextHolder.clearContext();

            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, Object>> registerUser(
            @Valid @RequestBody UserDTO registrationDto,
            BindingResult bindingResult,
            HttpServletRequest request,
            HttpServletResponse response) {

        Map<String, Object> responseMap = new HashMap<>();

        // Check for validation errors
        if (bindingResult.hasErrors()) {
            responseMap.put("success", false);
            responseMap.put("message", "Validation failed");
            responseMap.put("errors", bindingResult.getAllErrors());
            return ResponseEntity.badRequest().body(responseMap);
        }

        try {
            // Check if user already exists
            if (userService.existsByEmail(registrationDto.getEmail())) {
                responseMap.put("success", false);
                responseMap.put("message", "An account with this email already exists");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(responseMap);
            }

            if (userService.existsByUsername(registrationDto.getUsername())) {
                responseMap.put("success", false);
                responseMap.put("message", "Username is already taken");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(responseMap);
            }

            // Register the user
            User registeredUser = userService.registerNewUser(registrationDto);

            // Automatically log in the user after registration
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            registrationDto.getEmail(),
                            registrationDto.getPassword()
                    )
            );

            // Set authentication in security context
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // CRITICAL: Save the security context properly for Spring Security 6
            securityContextRepository.saveContext(
                    SecurityContextHolder.getContext(),
                    request,
                    response
            );

            // Prepare success response
            responseMap.put("success", true);
            responseMap.put("message", "Registration successful");
            responseMap.put("user", Map.of(
                    "id", registeredUser.getId(),
                    "username", registeredUser.getUsername(),
                    "email", registeredUser.getEmail(),
                    "firstName", registeredUser.getFirstName(),
                    "lastName", registeredUser.getLastName(),
                    "isNewUser", registeredUser.isNewUser()
            ));

            return ResponseEntity.status(HttpStatus.CREATED).body(responseMap);

        } catch (UserAlreadyExistsException e) {
            responseMap.put("success", false);
            responseMap.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(responseMap);

        } catch (Exception e) {
            responseMap.put("success", false);
            responseMap.put("message", "Registration failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseMap);
        }
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> loginUser(
            @RequestBody Map<String, String> loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {

        Map<String, Object> responseMap = new HashMap<>();

        try {
            String email = loginRequest.get("email");
            String password = loginRequest.get("password");

            // Authenticate user
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password)
            );

            // Set authentication in security context
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // CRITICAL: Save the security context properly for Spring Security 6
            securityContextRepository.saveContext(
                    SecurityContextHolder.getContext(),
                    request,
                    response
            );

            // Get user details with proper Optional handling
            Optional<User> optionalUser = userService.findByEmail(email);
            if (optionalUser.isPresent()) {
                User user = optionalUser.get();
                responseMap.put("success", true);
                responseMap.put("message", "Login successful");
                responseMap.put("user", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "firstName", user.getFirstName(),
                        "lastName", user.getLastName(),
                        "isNewUser", user.isNewUser()
                ));
            } else {
                responseMap.put("success", false);
                responseMap.put("message", "User not found after authentication");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseMap);
            }

            return ResponseEntity.ok(responseMap);

        } catch (Exception e) {
            responseMap.put("success", false);
            responseMap.put("message", "Invalid email or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseMap);
        }
    }
}
