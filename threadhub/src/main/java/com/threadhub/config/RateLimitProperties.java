package com.threadhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private int loginRequestsPerWindow = 5;
    private int registrationRequestsPerWindow = 5;
    private String windowDuration = "PT1M";

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
}
