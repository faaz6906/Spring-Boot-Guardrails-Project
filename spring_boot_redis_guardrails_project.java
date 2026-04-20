// ===============================
// COMPLETE SPRING BOOT PROJECT
// ===============================

// NOTE: Package name: com.example.guardrails

// -------------------------------
// 1. MAIN APPLICATION
// -------------------------------
package com.example.guardrails;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GuardrailsApplication {
    public static void main(String[] args) {
        SpringApplication.run(GuardrailsApplication.class, args);
    }
}

// -------------------------------
// 2. ENTITIES
// -------------------------------
package com.example.guardrails.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
public class User {
    @Id @GeneratedValue
    private Long id;
    private String username;
    private boolean isPremium;
}

@Entity
@Data
public class Bot {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String personaDescription;
}

@Entity
@Data
public class Post {
    @Id @GeneratedValue
    private Long id;
    private Long authorId;
    private String content;
    private LocalDateTime createdAt = LocalDateTime.now();
}

@Entity
@Data
public class Comment {
    @Id @GeneratedValue
    private Long id;
    private Long postId;
    private Long authorId;
    private String content;
    private int depthLevel;
    private LocalDateTime createdAt = LocalDateTime.now();
}

// -------------------------------
// 3. REPOSITORIES
// -------------------------------
package com.example.guardrails.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.guardrails.entity.*;

public interface UserRepository extends JpaRepository<User, Long> {}
public interface BotRepository extends JpaRepository<Bot, Long> {}
public interface PostRepository extends JpaRepository<Post, Long> {}
public interface CommentRepository extends JpaRepository<Comment, Long> {}

// -------------------------------
// 4. REDIS CONFIG
// -------------------------------
package com.example.guardrails.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        return template;
    }
}

// -------------------------------
// 5. SERVICE LAYER
// -------------------------------
package com.example.guardrails.service;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    public void updateVirality(Long postId, int score) {
        redisTemplate.opsForValue().increment("post:" + postId + ":virality", score);
    }

    public boolean checkBotLimit(Long postId) {
        Long count = redisTemplate.opsForValue().increment("post:" + postId + ":bot_count");
        return count <= 100;
    }

    public boolean checkCooldown(Long botId, Long userId) {
        String key = "cooldown:" + botId + ":" + userId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) return false;
        redisTemplate.opsForValue().set(key, "1", 10, TimeUnit.MINUTES);
        return true;
    }

    public void handleNotification(Long userId, String message) {
        String key = "notif:" + userId;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.opsForList().rightPush("pending:" + userId, message);
        } else {
            System.out.println("Push Notification Sent");
            redisTemplate.opsForValue().set(key, "1", 15, TimeUnit.MINUTES);
        }
    }
}

// -------------------------------
// 6. CONTROLLER
// -------------------------------
package com.example.guardrails.controller;

import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import com.example.guardrails.repository.*;
import com.example.guardrails.entity.*;
import com.example.guardrails.service.RedisService;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostRepository postRepo;
    private final CommentRepository commentRepo;
    private final RedisService redisService;

    @PostMapping
    public Post createPost(@RequestBody Post post) {
        return postRepo.save(post);
    }

    @PostMapping("/{postId}/comments")
    public Object addComment(@PathVariable Long postId, @RequestBody Comment comment,
                             @RequestParam(defaultValue = "false") boolean isBot,
                             @RequestParam(required = false) Long botId,
                             @RequestParam(required = false) Long userId) {

        if (comment.getDepthLevel() > 20) {
            return "Depth exceeded";
        }

        if (isBot) {
            if (!redisService.checkBotLimit(postId)) {
                return "429 Too Many Requests";
            }

            if (!redisService.checkCooldown(botId, userId)) {
                return "Cooldown active";
            }

            redisService.updateVirality(postId, 1);
            redisService.handleNotification(userId, "Bot replied");
        } else {
            redisService.updateVirality(postId, 50);
        }

        comment.setPostId(postId);
        return commentRepo.save(comment);
    }

    @PostMapping("/{postId}/like")
    public String likePost(@PathVariable Long postId) {
        redisService.updateVirality(postId, 20);
        return "Liked";
    }
}

// -------------------------------
// 7. SCHEDULER
// -------------------------------
package com.example.guardrails.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;

@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final RedisTemplate<String, Object> redisTemplate;

    @Scheduled(fixedRate = 300000)
    public void processNotifications() {
        Set<String> keys = redisTemplate.keys("pending:*");

        if (keys == null) return;

        for (String key : keys) {
            List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);

            if (messages != null && !messages.isEmpty()) {
                System.out.println("Summarized Notification: " + messages.size());
                redisTemplate.delete(key);
            }
        }
    }
}

// -------------------------------
// 8. application.properties
// -------------------------------

spring.datasource.url=jdbc:postgresql://localhost:5432/testdb
spring.datasource.username=user
spring.datasource.password=pass
spring.jpa.hibernate.ddl-auto=update
spring.redis.host=localhost
spring.redis.port=6379

// -------------------------------
// 9. docker-compose.yml
// -------------------------------

version: '3'
services:
  postgres:
    image: postgres
    environment:
      POSTGRES_DB: testdb
      POSTGRES_USER: user
      POSTGRES_PASSWORD: pass
    ports:
      - "5432:5432"

  redis:
    image: redis
    ports:
      - "6379:6379"

// ===============================
// END OF PROJECT
// ===============================
