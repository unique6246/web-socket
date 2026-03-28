package com.example.websocket;

import com.example.websocket.model.Role;
import com.example.websocket.model.User;
import com.example.websocket.model.UserStatus;
import com.example.websocket.repo.RoleRepository;
import com.example.websocket.repo.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

@SpringBootApplication(exclude = {
    RedisAutoConfiguration.class,
    RedisReactiveAutoConfiguration.class,
    RedisRepositoriesAutoConfiguration.class
})
@EnableAsync
public class WebSocketApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebSocketApplication.class, args);
    }

    @Bean
    public CommandLineRunner seedData(RoleRepository roleRepository,
                                      UserRepository userRepository,
                                      PasswordEncoder passwordEncoder) {
        return args -> {
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
            }

            // ── Always ensure latest profile info ────────────────────────
            admin.setEmail("admin@chatapp.com");
            admin.setDisplayName("Admin");
            admin.setBio("Platform administrator. Here to keep things running smoothly.");
            admin.setPhone("+1 000 000 0000");
            admin.setAvatarUrl(""); // set to a real Cloudinary URL if desired
            admin.setStatus(UserStatus.ONLINE);
            admin.setEmailVerified(true);
            admin.setPassword(passwordEncoder.encode("Admin@1234"));

            if (admin.getRoles() == null) admin.setRoles(new HashSet<>());
            boolean hasAdminRole = admin.getRoles().stream().anyMatch(r -> "ADMIN".equals(r.getName()));
            if (!hasAdminRole) admin.getRoles().add(adminRole);
            userRepository.save(admin);

            System.out.println("==> Admin user ready: username=admin  email=" + admin.getEmail()
                    + "  displayName=" + admin.getDisplayName()
                    + "  roles=" + admin.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.joining(",")));
        };
    }
}
