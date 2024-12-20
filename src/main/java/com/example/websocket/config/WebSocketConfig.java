package com.example.websocket.config;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtService jwtService;
    private final JwtUtil jwtUtil;
    private final SocketConnectionHandler socketConnectionHandler;
    public WebSocketConfig(JwtService jwtService, JwtUtil jwtUtil, SocketConnectionHandler socketConnectionHandler) {
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
        this.socketConnectionHandler = socketConnectionHandler;
    }


    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socketConnectionHandler, "/ws")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(@Nullable ServerHttpRequest request,@Nullable ServerHttpResponse response,
                                                   @Nullable WebSocketHandler wsHandler,@Nullable Map<String, Object> attributes) {
                        assert request != null;
                        String query = request.getURI().getQuery();
                        Map<String, String> queryParams = parseQueryParams(query);

                        // Get the token and roomName from queryParams
                        String token = queryParams.get("token");
                        String roomName = queryParams.get("roomName");
                        if (token != null && jwtService.validateToken(token)) {
                            String username = jwtUtil.extractUsername(token);
                            assert attributes != null;
                            attributes.put("username", username);
                            attributes.put("roomName", roomName);
                            return true;
                        }

                        assert response != null;
                        response.setStatusCode(HttpStatus.BAD_REQUEST);
                        return false;
                    }

                    @Override
                    public void afterHandshake(@Nullable ServerHttpRequest request,@Nullable ServerHttpResponse response,@Nullable WebSocketHandler wsHandler, Exception ex) {
                        // No post-handshake action needed
                    }
                })
                .setAllowedOrigins("*");
    }

    // Helper method to parse query parameters
    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    params.put(pair.substring(0, idx), pair.substring(idx + 1));
                }
            }
        }
        return params;
    }

}
