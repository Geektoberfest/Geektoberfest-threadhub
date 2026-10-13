package com.threadhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private int loginRequestsPerWindow = 5;
    private int registrationRequestsPerWindow = 5;
    private String windowDuration = "PT1M";

    /**
     * Reverse proxies this application operates itself, given as exact IP literals or IPv4 CIDR
     * blocks. Client IP resolution trusts {@code X-Forwarded-For} only when the request arrives
     * from one of these addresses. The default is empty, which means forwarded headers are never
     * trusted and {@code request.getRemoteAddr()} is always used.
     */
    private List<String> trustedProxies = new ArrayList<>();

    public int getLoginRequestsPerWindow() {
        return loginRequestsPerWindow;
    }

    public void setLoginRequestsPerWindow(int loginRequestsPerWindow) {
        this.loginRequestsPerWindow = loginRequestsPerWindow;
    }

    public int getRegistrationRequestsPerWindow() {
        return registrationRequestsPerWindow;
    }

    public void setRegistrationRequestsPerWindow(int registrationRequestsPerWindow) {
        this.registrationRequestsPerWindow = registrationRequestsPerWindow;
    }

    public String getWindowDuration() {
        return windowDuration;
    }

    public void setWindowDuration(String windowDuration) {
        this.windowDuration = windowDuration;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }
}
