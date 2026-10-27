package com.openhire.applicationservice.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class PartnerIndexes {
    @Bean
    ApplicationRunner partnerPageIndexes(MongoTemplate mongo) {
        return args -> {
            mongo.indexOps("agency_assignments")
                    .createIndex(new Index()
                            .on("tenantId", Sort.Direction.ASC)
                            .on("actorId", Sort.Direction.ASC)
                            .on("active", Sort.Direction.ASC)
                            .on("_id", Sort.Direction.ASC)
                            .named("partner_assignment_page"));
            mongo.indexOps("submissions")
                    .createIndex(new Index()
                            .on("tenantId", Sort.Direction.ASC)
                            .on("actorId", Sort.Direction.ASC)
                            .on("source", Sort.Direction.ASC)
                            .on("submittedAt", Sort.Direction.DESC)
                            .on("_id", Sort.Direction.DESC)
                            .named("partner_submission_page"));
            mongo.indexOps("submissions")
                    .createIndex(new Index()
                            .on("tenantId", Sort.Direction.ASC)
                            .on("actorId", Sort.Direction.ASC)
                            .on("source", Sort.Direction.ASC)
                            .on("jobId", Sort.Direction.ASC)
                            .on("stage", Sort.Direction.ASC)
                            .named("partner_job_counts"));
        };
    }
}
