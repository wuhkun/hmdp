package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    // 注入配置文件中的Redis参数
    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")  // :0 是默认值，配置文件没配时用0
    private int redisDatabase;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 拼接地址：redis://IP:端口
        String redisAddress = "redis://" + redisHost + ":" + redisPort;

        config.useSingleServer()
                .setAddress(redisAddress)       // 使用占位符读取的地址
                .setPassword(redisPassword)     // 使用占位符读取的密码
                .setDatabase(redisDatabase);    // 使用占位符读取的数据库编号

        return Redisson.create(config);
    }
}