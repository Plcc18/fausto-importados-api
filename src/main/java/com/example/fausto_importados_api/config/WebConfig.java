package com.example.fausto_importados_api.config;

import com.example.fausto_importados_api.security.reauth.ReauthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ReauthInterceptor reauthInterceptor;

    public WebConfig(ReauthInterceptor reauthInterceptor) {
        this.reauthInterceptor = reauthInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(reauthInterceptor);
    }
}
