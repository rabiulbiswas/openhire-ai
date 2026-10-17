package com.openhire.integration.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_RECOVERY_TESTS", matches = "true")
class RecoveryRehearsalTest {
    @Test
    void encryptedBackupCanRestoreOnlyIntoAnIsolatedDatabase() throws Exception {
        String source = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        String restored = null;
        Path directory = Files.createTempDirectory("openhire-recovery-test-");
        Path config = directory.resolve("mongo.yml"), archive = directory.resolve("backup.oha");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        Files.writeString(
                config,
                "uri: \"mongodb://localhost:27017/?serverSelectionTimeoutMS=3000\"\n",
                StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        try (var mongo = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                mongo.getDatabase(source)
                        .getCollection("fixture")
                        .insertOne(new Document("_id", "one").append("value", "isolated-test-data"));
                run(config, encoded, "backup", archive.toString(), source);
                assertTrue(run(config, encoded, "verify", archive.toString()).contains("AUTHENTICATED"));
                assertTrue(run(config, encoded, "rehearse", archive.toString()).contains("DRY_RUN"));
                String output = run(config, encoded, "rehearse", archive.toString(), "--execute");
                var target = java.util.regex.Pattern.compile("oh_restore_[a-f0-9]{32}")
                        .matcher(output);
                assertTrue(target.find());
                restored = target.group();
                assertEquals(
                        1, mongo.getDatabase(restored).getCollection("fixture").countDocuments());
                assertEquals(
                        "isolated-test-data",
                        mongo.getDatabase(restored)
                                .getCollection("fixture")
                                .find()
                                .first()
                                .getString("value"));
                assertEquals(
                        1, mongo.getDatabase(source).getCollection("fixture").countDocuments());
            } finally {
                mongo.getDatabase(source).drop();
                if (restored != null && restored.matches("oh_restore_[a-f0-9]{32}"))
                    mongo.getDatabase(restored).drop();
            }
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(config);
            Files.delete(directory);
        }
    }

    private String run(Path config, String key, String... args) throws Exception {
        var command = new ArrayList<>(List.of(
                "node",
                Path.of("../scripts/mongo-recovery.mjs").toAbsolutePath().toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("RECOVERY_MONGO_CONFIG", config.toString());
        builder.environment().put("RECOVERY_ENCRYPTION_KEY", key);
        var process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Recovery rehearsal exceeded 45 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
