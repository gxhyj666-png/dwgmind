package com.dwgmind.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人机协同复核通道（WebSocket）
 *
 * 用途：多个评审/工程师同时在线标注复核同一张图纸时，
 * 一人的确认/修正动作实时广播给其他在线者。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ReviewSocketHandler(), "/ws/review")
                .setAllowedOriginPatterns("*");
    }

    public static class ReviewSocketHandler extends TextWebSocketHandler {

        private static final Map<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            SESSIONS.put(session.getId(), session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            SESSIONS.remove(session.getId());
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            // 广播给其他人（不回声给发送者）
            SESSIONS.forEach((id, s) -> {
                if (!id.equals(session.getId()) && s.isOpen()) {
                    try {
                        s.sendMessage(new TextMessage(message.getPayload()));
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }
}
