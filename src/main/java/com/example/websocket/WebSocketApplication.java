package com.example.websocket;

import com.example.websocket.model.Role;
import com.example.websocket.model.User;
import com.example.websocket.repo.RoleRepository;
import com.example.websocket.repo.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@SpringBootApplication
public class WebSocketApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebSocketApplication.class, args);
    }

    @Bean
    public CommandLineRunner seedData(RoleRepository roleRepository,
                                      UserRepository userRepository,
                                      PasswordEncoder passwordEncoder,
                                      CacheManager cacheManager) {
        return args -> {
            // Flush stale userDetails cache on every startup
            try {
                var userDetailsCache = cacheManager.getCache("userDetails");
                if (userDetailsCache != null) {
                    userDetailsCache.clear();
                }
            } catch (Exception e) {
                System.out.println("==> Warning: could not clear userDetails cache: " + e.getMessage());
            }

            // Seed roles
            List<String> roleNames = Arrays.asList("ADMIN", "USER", "MODERATOR");
            for (String roleName : roleNames) {
                if (roleRepository.findByName(roleName).isEmpty()) {
                    Role role = new Role();
                    role.setName(roleName);
                    roleRepository.save(role);
                }
            }

            // Seed or repair default admin user
            Role adminRole = roleRepository.findByName("ADMIN")
                    .orElseThrow(() -> new RuntimeException("ADMIN role not found"));

            User admin = userRepository.findByUsername("admin");
            if (admin == null) {
                admin = new User();
                admin.setUsername("admin");
                admin.setEmail("admin@chatapp.com");
            }
            // Always ensure correct password and role on every startup
            admin.setPassword(passwordEncoder.encode("admin123"));
            if (admin.getRoles() == null) admin.setRoles(new HashSet<>());
            boolean hasAdminRole = admin.getRoles().stream().anyMatch(r -> "ADMIN".equals(r.getName()));
            if (!hasAdminRole) admin.getRoles().add(adminRole);
            userRepository.save(admin);
            System.out.println("==> Admin user ready: username=admin password=admin123 roles=" + admin.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.joining(",")));
        };
    }
}
