package com.example.websocket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static frontend files only.
        // File uploads go to Cloudinary CDN — no local /uploads/ handler needed.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}
