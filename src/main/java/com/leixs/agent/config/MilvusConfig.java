package com.leixs.agent.config;

import com.leixs.agent.client.MilvusClientFactory;
import io.milvus.client.MilvusServiceClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    private static final Logger logger = LoggerFactory.getLogger(MilvusConfig.class);

    @Autowired
    private MilvusClientFactory milvusClientFactory;

    private MilvusServiceClient milvusClient;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        logger.info("正在初始化 Milvus 客户端...");
        milvusClient = milvusClientFactory.createClient();
        logger.info("Milvus 客户端初始化完成");
        return milvusClient;
    }

    @PreDestroy
    public void cleanUp() {
        if (milvusClient != null) {
            logger.info("正在关闭 Milvus 客户端连接...");
            milvusClient.close();
            logger.info("Milvus 客户端连接已关闭");
        }
    }

}
