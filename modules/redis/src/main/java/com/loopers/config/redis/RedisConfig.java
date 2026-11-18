package com.loopers.config.redis;


import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;
import java.util.function.Consumer;

@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig{
    private static final String CONNECTION_MASTER = "redisConnectionMaster";
    private static final String CONNECTION_PUB_SUB = "redisConnectionPubSub";
    public static final String REDIS_TEMPLATE_MASTER = "redisTemplateMaster";

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties){
        this.redisProperties = redisProperties;
    }

    @Primary
    @Bean
    public LettuceConnectionFactory defaultRedisConnectionFactory() {
        int database = redisProperties.database();
        RedisNodeInfo master = redisProperties.master();
        List<RedisNodeInfo> replicas = redisProperties.replicas();
        return lettuceConnectionFactory(
                database, master, replicas,
                b -> b.readFrom(ReadFrom.REPLICA_PREFERRED)
        );
    }

    @Qualifier(CONNECTION_MASTER)
    @Bean
    public LettuceConnectionFactory masterRedisConnectionFactory() {
        int database = redisProperties.database();
        RedisNodeInfo master = redisProperties.master();
        List<RedisNodeInfo> replicas = redisProperties.replicas();
        return lettuceConnectionFactory(
                database, master, replicas,
                b -> b.readFrom(ReadFrom.MASTER)
        );
    }

    @Primary
    @Bean
    public RedisTemplate<String, String> defaultRedisTemplate(LettuceConnectionFactory lettuceConnectionFactory) {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        return defaultRedisTemplate(redisTemplate, lettuceConnectionFactory);
    }

    @Qualifier(REDIS_TEMPLATE_MASTER)
    @Bean
    public RedisTemplate<String, String> masterRedisTemplate(
            @Qualifier(CONNECTION_MASTER) LettuceConnectionFactory lettuceConnectionFactory
    ) {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        return defaultRedisTemplate(redisTemplate, lettuceConnectionFactory);
    }

    /**
     * pub/sub 전용 연결. master 노드에 단독(standalone)으로 붙는다.
     * Spring Data Redis 는 master/replica 구성의 연결에서 pub/sub 을 지원하지 않는다 (StaticMasterReplicaConnectionProvider).
     */
    @Qualifier(CONNECTION_PUB_SUB)
    @Bean
    public LettuceConnectionFactory pubSubRedisConnectionFactory() {
        RedisNodeInfo master = redisProperties.master();
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(master.host(), master.port());
        standalone.setDatabase(redisProperties.database());
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();
        if(redisProperties.timeout() != null) builder.commandTimeout(redisProperties.timeout());
        return new LettuceConnectionFactory(standalone, builder.build());
    }

    /**
     * pub/sub 수신 컨테이너. 리스너를 등록한 앱만 구독 연결을 연다.
     * 메시지는 수신 스레드에서 바로 리스너를 부른다 (SyncTaskExecutor). 리스너는 짧게 끝나야 한다.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            @Qualifier(CONNECTION_PUB_SUB) LettuceConnectionFactory pubSubRedisConnectionFactory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(pubSubRedisConnectionFactory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.setSubscriptionExecutor(new SimpleAsyncTaskExecutor("redis-subscription-"));
        return container;
    }

    private LettuceConnectionFactory lettuceConnectionFactory(
            int database,
            RedisNodeInfo master,
            List<RedisNodeInfo> replicas,
            Consumer<LettuceClientConfiguration.LettuceClientConfigurationBuilder> customizer
    ){
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();
        if(redisProperties.timeout() != null) builder.commandTimeout(redisProperties.timeout());
        if(customizer != null) customizer.accept(builder);
        LettuceClientConfiguration clientConfig = builder.build();
        RedisStaticMasterReplicaConfiguration masterReplicaConfig = new RedisStaticMasterReplicaConfiguration(master.host(), master.port());
        masterReplicaConfig.setDatabase(database);
        for(RedisNodeInfo r : replicas){
            masterReplicaConfig.addNode(r.host(), r.port());
        }
        return new LettuceConnectionFactory(masterReplicaConfig, clientConfig);
    }

    private <K,V> RedisTemplate<K,V> defaultRedisTemplate(
            RedisTemplate<K,V> template,
            LettuceConnectionFactory connectionFactory
    ){
        StringRedisSerializer s = new StringRedisSerializer();
        template.setKeySerializer(s);
        template.setValueSerializer(s);
        template.setHashKeySerializer(s);
        template.setHashValueSerializer(s);
        template.setConnectionFactory(connectionFactory);
        return template;
    }
}
