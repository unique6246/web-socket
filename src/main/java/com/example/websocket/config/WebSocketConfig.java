package com.example.websocket.config;

import com.example.websocket.JWT.WsTicketService;
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

/**
 * WebSocket connections are authenticated via a one-time ticket (not the JWT).
 * Before opening a WS connection, the frontend calls POST /api/auth/ws-ticket
 * which returns a random 30-second ticket. That ticket is passed in the WS URL.
 * This way the real JWT never appears in the WebSocket URL or browser history.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsTicketService wsTicketService;
    private final SocketConnectionHandler socketConnectionHandler;

    public WebSocketConfig(WsTicketService wsTicketService,
                           SocketConnectionHandler socketConnectionHandler) {
        this.wsTicketService = wsTicketService;
        this.socketConnectionHandler = socketConnectionHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socketConnectionHandler, "/ws")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(@Nullable ServerHttpRequest request,
                                                   @Nullable ServerHttpResponse response,
                                                   @Nullable WebSocketHandler wsHandler,
                                                   @Nullable Map<String, Object> attributes) {
                        assert request != null;
                        assert response != null;
                        assert attributes != null;

                        Map<String, String> params = parseQueryParams(request.getURI().getQuery());
                        String ticket   = params.get("ticket");
                        String roomName = params.get("roomName");

                        // Validate one-time ticket — consumes it immediately
                        String username = wsTicketService.consumeTicket(ticket);
                        if (username == null) {
                            response.setStatusCode(HttpStatus.UNAUTHORIZED);
                            return false;
                        }

                        attributes.put("username", username);
                        attributes.put("roomName", roomName);
                        return true;
                    }

                    @Override
                    public void afterHandshake(@Nullable ServerHttpRequest request,
                                               @Nullable ServerHttpResponse response,
                                               @Nullable WebSocketHandler wsHandler,
                                               Exception ex) {}
                })
                .setAllowedOriginPatterns("http://localhost:*", "https://localhost:*");
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key   = pair.substring(0, idx);
                    String value = pair.substring(idx + 1);
                    try { value = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8); }
                    catch (Exception ignored) {}
                    params.put(key, value);
                }
            }
        }
        return params;
    }
}
