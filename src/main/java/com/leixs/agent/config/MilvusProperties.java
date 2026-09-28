package com.leixs.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private String host = "localhost";

    private Integer port = 19530;

    private String username = "";

    private String password = "";

    private String database = "default";

    private Long timeout = 10000L;

    public String getAddress() {
        return host +":" + port;
    }
}
