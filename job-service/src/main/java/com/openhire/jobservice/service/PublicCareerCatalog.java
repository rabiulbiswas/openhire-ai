package com.openhire.jobservice.service;

import com.openhire.platform.web.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.stereotype.Service;

/** Public, tenant-scoped read model. Never serialize staff job objects or required answers. */
@Service
public class PublicCareerCatalog {
    private final MongoTemplate mongo;

    public PublicCareerCatalog(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Document list(String tenant, int page, int size, String keyword, String location, String category) {
        if (page < 1 || page > 100000 || size < 1 || size > 50)
            throw new IllegalArgumentException("Page must be positive and page size between 1 and 50");
        keyword = text(keyword, 200);
        location = text(location, 200);
        category = text(category, 100);
        var stages = base(tenant);
        var filter = new Document();
        if (!keyword.isEmpty()) {
            var regex = new Document("$regex", Pattern.quote(keyword)).append("$options", "i");
            filter.put(
                    "$or",
                    List.of(
                            new Document("title", regex),
                            new Document("team", regex),
                            new Document("location", regex),
                            new Document("careerVersion.description", regex)));
        }
        if (!location.isEmpty()) filter.put("location", location);
        if (!category.isEmpty()) filter.put("careerVersion.classification.categoryId", category);
        var rows = new ArrayList<Document>();
        rows.add(new Document("$match", filter));
        rows.add(new Document("$sort", new Document("publishedAt", -1).append("_id", 1)));
        rows.add(new Document("$skip", (long) (page - 1) * size));
        rows.add(new Document("$limit", size));
        rows.add(new Document("$project", projection(false)));
        // Filter choices use the whole published catalog, not only the current page.
        stages.add(new Document(
                "$facet",
                new Document("data", rows)
                        .append("total", List.of(new Document("$match", filter), new Document("$count", "value")))
                        .append(
                                "locations",
                                List.of(
                                        new Document("$group", new Document("_id", "$location")),
                                        new Document("$sort", new Document("_id", 1))))
                        .append(
                                "categories",
                                List.of(
                                        new Document(
                                                "$group",
                                                new Document("_id", "$careerVersion.classification.categoryId")
                                                        .append(
                                                                "name",
                                                                new Document(
                                                                        "$first",
                                                                        "$careerVersion.classification.categoryName"))),
                                        new Document("$sort", new Document("name", 1))))));
        var result = aggregate(stages).getFirst();
        var counts = result.getList("total", Document.class);
        long total = counts.isEmpty() ? 0 : ((Number) counts.getFirst().get("value")).longValue();
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", size);
        result.put("totalPages", (total + size - 1) / size);
        result.put(
                "locations",
                result.getList("locations", Document.class).stream()
                        .map(row -> row.getString("_id"))
                        .filter(value -> value != null && !value.isBlank())
                        .toList());
        result.put(
                "categories",
                result.getList("categories", Document.class).stream()
                        .filter(row -> row.getString("_id") != null && row.getString("name") != null)
                        .map(row -> new Document("id", row.getString("_id")).append("name", row.getString("name")))
                        .toList());
        return result;
    }

    public Document detail(String tenant, String id) {
        var stages = base(tenant);
        stages.add(new Document("$match", new Document("_id", text(id, 100))));
        stages.add(new Document("$project", projection(true)));
        var rows = aggregate(stages);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Career listing is not available");
        return rows.getFirst();
    }

    private List<Document> base(String tenant) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 100)
            throw new IllegalArgumentException("Tenant is required");
        var stages = new ArrayList<Document>();
        stages.add(new Document(
                "$match",
                new Document("tenantId", tenant)
                        .append("status", "OPEN")
                        .append("approvedVersion", new Document("$gt", 0))
                        .append("$expr", new Document("$eq", List.of("$approvedVersion", "$currentVersion")))));
        stages.add(new Document(
                "$set",
                new Document(
                        "careerVersion",
                        new Document(
                                "$arrayElemAt",
                                List.of(
                                        new Document(
                                                "$filter",
                                                new Document("input", "$versions")
                                                        .append("as", "version")
                                                        .append(
                                                                "cond",
                                                                new Document(
                                                                        "$eq",
                                                                        List.of(
                                                                                "$$version.version",
                                                                                "$approvedVersion")))),
                                        0)))));
        return stages;
    }

    private Document projection(boolean detail) {
        var fields = new Document("_id", 0)
                .append("id", "$_id")
                .append("title", 1)
                .append("team", 1)
                .append("location", 1)
                .append("level", 1)
                .append("publishedAt", 1)
                .append("categoryId", "$careerVersion.classification.categoryId")
                .append("categoryName", "$careerVersion.classification.categoryName")
                .append("workMode", "$careerVersion.classification.workMode");
        if (detail) {
            fields.append("description", "$careerVersion.description")
                    .append("mustHaveSkills", "$careerVersion.mustHaveSkills")
                    .append("qualification", "$careerVersion.qualification")
                    .append("goodToHaveSkills", "$careerVersion.goodToHaveSkills")
                    .append("headcount", "$careerVersion.headcount")
                    .append(
                            "salaryBand",
                            new Document("currency", "$currency")
                                    .append("min", new Document("$toDouble", "$salaryMin"))
                                    .append("max", new Document("$toDouble", "$salaryMax")))
                    .append(
                            "questions",
                            new Document(
                                    "$map",
                                    new Document("input", "$careerVersion.knockoutQuestions")
                                            .append("as", "question")
                                            .append(
                                                    "in",
                                                    new Document("id", "$$question.id")
                                                            .append("question", "$$question.question"))));
        }
        return fields;
    }

    private List<Document> aggregate(List<Document> stages) {
        var operations = stages.stream()
                .<org.springframework.data.mongodb.core.aggregation.AggregationOperation>map(stage -> context -> stage)
                .toList();
        return mongo.aggregate(Aggregation.newAggregation(operations), "jobs", Document.class)
                .getMappedResults();
    }

    private String text(String value, int max) {
        if (value == null) return "";
        if (value.length() > max) throw new IllegalArgumentException("Search value is too long");
        return value.trim();
    }
}
