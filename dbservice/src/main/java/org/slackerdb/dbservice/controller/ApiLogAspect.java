package org.slackerdb.dbservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.aspectj.lang.annotation.Aspect;

@Aspect
@Component
public class ApiLogAspect {
    private static final Logger log = LoggerFactory.getLogger(ApiLogAspect.class);

    @Around("execution(* org.slackerdb.dbservice..*controller.*(..))")
    public Object logApi(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        // 获取 HttpServletRequest
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;

        String ip = request != null ? request.getRemoteAddr() : "unknown";
        String uri = request != null ? request.getRequestURI() : "unknown";

        log.trace("Request begin: {} from IP: {}", uri, ip);

        Object result = joinPoint.proceed();

        long timeTaken = System.currentTimeMillis() - start;

        log.trace("Request end: {} elapsed: {} ms", uri, timeTaken);

        return result;
    }
}
