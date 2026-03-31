package com.study.zhiguang.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 配置 Spring Security 过滤链。
     *
     * <p>主要包含：</p>
     * - 关闭 CSRF；
     * - 启用 CORS；
     * - 使用无状态会话策略；
     * - 公开认证接口与健康检查，其余接口需鉴权；
     * - 启用资源服务器的 JWT 校验。
     *
     * @param http Spring 的 {@link HttpSecurity} 构建器。
     * @return 构建完成的 {@link SecurityFilterChain}。
     * @throws Exception 构建过滤链过程中可能抛出的异常。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http.csrf(AbstractHttpConfigurer::disable)
        // 关闭 CSRF（跨站请求伪造）保护。
        .cors(Customizer.withDefaults())
        // 启用 CORS（跨域资源共享）支持，并使用默认配置。 这边默认配置会查找名为 corsConfigurationSource 的 Bean（下文定义），用于决定哪些跨域请求被允许。
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // 配置会话管理策略为 STATELESS（无状态），不会创建或使用http会话，所有请求依赖服务端session，符合jwt模式
        .authorizeHttpRequests(auth -> auth  //authorizeHttpRequests 开始配置基于 HTTP 请求的授权规则
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // 公开内容：首页 Feed 不需要登录，允许所有用户（无需认证）访问
                .requestMatchers("/api/v1/knowposts/feed").permitAll()
                // 知文详情（公开已发布内容，非公开由服务层校验）
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/knowposts/detail/*").permitAll()
                // 知文详情页 RAG 问答（SSE 流式输出）允许匿名访问
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/knowposts/*/qa/stream").permitAll()
                .requestMatchers(
                        "/api/v1/auth/send-code",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/token/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/password/reset"
                ).permitAll()
                // 将认证相关的所有接口（发送验证码、注册、登录、刷新令牌、登出、重置密码）开放给匿名用户，因为这些接口正是用户获取凭证的入口。
                .anyRequest().authenticated() //除了上述明确放行的请求之外，其他所有请求都需要认证（即携带有效的 JWT）。
        )
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        // 将应用配置为 OAuth2 资源服务器，使用 JWT 作为令牌。
        //
        //jwt(Customizer.withDefaults())
        // 表示启用默认的 JWT 配置，它会自动从请求头 Authorization: Bearer <token> 中解析 JWT，并验证其签名、有效期等。
        return http.build();
    }

    /**
     * 定义并提供 CORS 配置源。
     *
     * <p>当前允许所有来源（后续建议替换为产品白名单），允许常见方法与请求头，且不携带凭证。</p>
     *
     * @return {@link CorsConfigurationSource}，用于为所有路径注册 CORS 规则。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*")); // TODO replace with product whitelist
        // 允许所有来源（域名）发起跨域请求。
        // * 是通配符，表示任何源。注意：在生产环境中应当替换为具体的白名单域名（如 https://example.com），避免安全隐患。
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 允许的 HTTP 方法：GET、POST、PUT、DELETE、OPTIONS。OPTIONS 是预检请求必须的方法
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        //允许客户端在请求中携带的头部字段：Authorization（用于 JWT）、Content-Type（指定内容类型）、X-Requested-With（标识 Ajax 请求）。
        configuration.setAllowCredentials(false);
        // 是否允许携带凭证（如 Cookie、HTTP 认证信息）。设置为 false 表示不允许，因为当前配置允许所有来源 *，而允许凭证时不能使用 *，必须指定具体源。
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        //创建 UrlBasedCorsConfigurationSource，并为所有路径 (/**) 注册上面定义的 CORS 配置。这样对所有接口都应用相同的跨域规则。
        return source;
    }

}
