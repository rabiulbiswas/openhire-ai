package com.openhire.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class MongoIndexConfiguration {
    @Bean
    Object identityIndexes(MongoTemplate mongo) {
        mongo.indexOps("organizations")
                .createIndex(new Index().on("slug", Sort.Direction.ASC).unique());
        mongo.indexOps("users")
                .createIndex(new Index()
                        .on("organizationId", Sort.Direction.ASC)
                        .on("email", Sort.Direction.ASC)
                        .unique());
        mongo.indexOps("roles")
                .createIndex(new Index()
                        .on("organizationId", Sort.Direction.ASC)
                        .on("name", Sort.Direction.ASC)
                        .unique());
        mongo.indexOps("invites")
                .createIndex(new Index().on("tokenHash", Sort.Direction.ASC).unique());
        mongo.indexOps("api_keys")
                .createIndex(new Index().on("prefix", Sort.Direction.ASC).unique());
        mongo.indexOps("setup_progress")
                .createIndex(
                        new Index().on("organizationId", Sort.Direction.ASC).unique());
        mongo.indexOps("tenant_security_settings")
                .createIndex(
                        new Index().on("organizationId", Sort.Direction.ASC).unique());
        return new Object();
    }
}
