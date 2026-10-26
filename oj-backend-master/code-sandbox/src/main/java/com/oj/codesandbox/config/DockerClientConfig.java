package com.oj.codesandbox.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

@Configuration
// 只在开启docker代码沙箱时加载
@ConditionalOnProperty(name = "oj.code-sandbox.type", havingValue = "docker")
@RefreshScope
public class DockerClientConfig {
    @Value("${oj.code-sandbox.docker.docker-host:tcp://127.0.0.1:2375}")
    private String dockerHost;
    @Value("${oj.code-sandbox.docker.max-connections:100}")
    private int maxConnections;
    @Value("${oj.code-sandbox.docker.connect-timeout:30}")
    private int connectTimeout;
    @Value("${oj.code-sandbox.docker.response-timeout:30}")
    private int responseTimeout;

    @Bean
    public DockerClient dockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(maxConnections)
                .connectionTimeout(Duration.ofSeconds(connectTimeout))
                .responseTimeout(Duration.ofSeconds(responseTimeout))
                .build();

        DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);

        return dockerClient;
    }
}
