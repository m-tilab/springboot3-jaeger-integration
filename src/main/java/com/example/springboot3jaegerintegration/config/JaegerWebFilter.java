package com.example.springboot3jaegerintegration.config;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.Inet6Address;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Configuration
@Order(Integer.MIN_VALUE)
public class JaegerWebFilter implements WebFilter {

    @Value("${application.name}")
    private String applicationName;

    @Autowired
    private Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        Span span = tracer.buildSpan(applicationName).start();

        Scope scope = tracer.activateSpan(span);

        return chain.filter(exchange).doFinally(signalType -> {

            span.log(spanLogDecorator(exchange, span));

            scope.close();
            span.finish();
        });
    }

    @Nullable
    private Map<String, Object> spanLogDecorator(ServerWebExchange exchange, Span span) {

        final Object handler = exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler == null) {
            return null;
        }

        final Map<String, Object> logs = new HashMap<>(4);
        logs.put("event", "handle");

        final Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        final String patternAsString = pattern == null ? null : pattern.toString();
        if (pattern != null) {
            logs.put("handler", patternAsString);
        }

        if (handler instanceof HandlerMethod handlerMethod) {

            final String methodName = handlerMethod.getMethod().getName();
            logs.put("handler.method_name", handlerMethod.getMethod().getName());
            span.setOperationName(methodName);
            logs.put("handler.class_simple_name", handlerMethod.getBeanType().getSimpleName());
        } else {
            if (pattern != null) {
                span.setOperationName(patternAsString);
            }
            logs.put("handler.class_simple_name", handler.getClass().getSimpleName());
        }

        Tags.COMPONENT.set(span, "java-spring-webflux");
        final ServerHttpRequest request = exchange.getRequest();
        Tags.HTTP_METHOD.set(span, request.getMethod().name());
        Tags.HTTP_URL.set(span, request.getURI().toString());
        Optional.ofNullable(request.getRemoteAddress()).ifPresent(remoteAddress -> {
            Tags.PEER_HOSTNAME.set(span, remoteAddress.getHostString());
            Tags.PEER_PORT.set(span, remoteAddress.getPort());
            Optional.ofNullable(remoteAddress.getAddress()).ifPresent(inetAddress -> {
                if (inetAddress instanceof Inet6Address) {
                    Tags.PEER_HOST_IPV6.set(span, inetAddress.getHostAddress());
                } else {
                    Tags.PEER_HOST_IPV4.set(span, inetAddress.getHostAddress());
                }
            });
        });

        return logs;
    }
}
