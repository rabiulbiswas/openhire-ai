package com.openhire.applicationservice.service;

import org.bson.Document;
import org.bson.json.JsonParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Parses the multipart {@code answers} field; malformed JSON is the caller's mistake, not a server error. */
public final class Answers {
    private Answers() {}

    public static Document parse(String json) {
        try {
            return Document.parse(json);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answers must be a JSON object");
        }
    }
}
