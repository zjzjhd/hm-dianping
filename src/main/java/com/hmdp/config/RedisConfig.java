package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zjzjhd
 * @version 1.0
 * @description: TODO
 * @date 2023/2/27 14:23
 */

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissionClient() {
        // 配置类
        Config config = new Config();

        // 添加 Redis 地址，此处添加了单点的地址，也可以使用 config.useClusterServers() 添加集群地址
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");

        // 创建客户端
        return Redisson.create(config);
    }

}
