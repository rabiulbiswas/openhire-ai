package com.openhire.applicationservice.controller;

import static com.openhire.support.Records.text;

import com.openhire.applicationservice.dto.PartnerPage;
import com.openhire.applicationservice.repository.mongo.PartnerRepository;
import com.openhire.support.Access;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class EmployeeSubmissionController {
    private final PartnerRepository repository;
    private final Access access;

    public EmployeeSubmissionController(PartnerRepository repository, Access access) {
        this.repository = repository;
        this.access = access;
    }

    @GetMapping("/me/referrals")
    public PartnerPage<Document> submissions(
            Authentication auth,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "") String before,
            @RequestParam(defaultValue = "25") int limit) {
        if (!access.role(auth, "EMPLOYEE") && !access.staff(auth))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee access required");
        return repository
                .employeeSubmissions(access.tenant(auth), access.actor(auth), after, before, limit)
                .map(row -> new Document("id", text(row, "_id"))
                        .append("name", text(row, "name"))
                        .append("jobTitle", text(row, "jobTitle"))
                        .append("stage", PartnerController.publicStage(text(row, "stage")))
                        .append("submittedAt", text(row, "submittedAt")));
    }
}
