package com.shenchen.cloudcoldagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
@MapperScan("com.shenchen.cloudcoldagent.mapper")
public class CloudColdAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudColdAgentApplication.class, args);
    }

}
