//package com.project.boongobbang.config;
//
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.servlet.config.annotation.CorsRegistry;
//import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//
//@Configuration
//public class WebConfiguration implements WebMvcConfigurer {
//    @Override
//    public void addCorsMappings(CorsRegistry registry) {
//        registry.addMapping("/**")
//                .allowedMethods("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
//                .allowedHeaders("*")
//                .allowedOrigins(
//                        "http://boong-vpc-ec2-deploy-lb-999176414.ap-northeast-2.elb.amazonaws.com",
//                        "http://d2bczezs33iv0e.cloudfront.net",
//                        "http://boong-o-bbang.com/"
//                )
//                .allowCredentials(true);
//    }
//}
