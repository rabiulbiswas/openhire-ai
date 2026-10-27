package com.openhire.employee.controller;

import com.openhire.employee.dto.EmployeeViews.EmployeeDetail;
import com.openhire.employee.dto.EmployeeViews.MyOnboardingView;
import com.openhire.employee.dto.EmployeeViews.OnboardingView;
import com.openhire.employee.dto.EmployeeViews.Page;
import com.openhire.employee.dto.EmployeeViews.RewardView;
import com.openhire.employee.dto.EmployeeViews.SagaView;
import com.openhire.employee.service.EmployeeApplicationService;
import com.openhire.employee.service.HireSagaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class EmployeeController {
    private final EmployeeApplicationService employees;
    private final HireSagaService sagas;
    private final Viewers viewers;

    EmployeeController(EmployeeApplicationService employees, HireSagaService sagas, Viewers viewers) {
        this.employees = employees;
        this.sagas = sagas;
        this.viewers = viewers;
    }

    record TaskInput(Boolean done) {
        boolean value() {
            if (done == null) throw new IllegalArgumentException("done is required");
            return done;
        }
    }

    @GetMapping("/employees")
    com.openhire.employee.dto.EmployeeViews.EmployeePage list(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String joiningFrom,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "") String before,
            @RequestParam(defaultValue = "25") int limit) {
        viewers.staff(auth);
        return employees.listPage(viewers.tenant(auth), status, joiningFrom, after, before, limit);
    }

    @GetMapping("/employees/{id}")
    EmployeeDetail detail(Authentication auth, @PathVariable String id) {
        viewers.staff(auth);
        return employees.detail(viewers.tenant(auth), id);
    }

    @PostMapping("/employees/{id}/activate")
    EmployeeDetail activate(Authentication auth, @PathVariable String id) {
        var actor = viewers.staff(auth);
        return employees.activate(viewers.tenant(auth), id, actor);
    }

    @GetMapping("/employees/{id}/onboarding")
    OnboardingView onboarding(Authentication auth, @PathVariable String id) {
        return employees.onboarding(viewers.tenant(auth), id, viewers.viewer(auth));
    }

    @PatchMapping("/employees/{id}/onboarding/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void toggle(
            Authentication auth, @PathVariable String id, @PathVariable String taskId, @RequestBody TaskInput body) {
        employees.toggleTask(viewers.tenant(auth), id, taskId, body.value(), viewers.viewer(auth));
    }

    @GetMapping("/me/onboarding")
    MyOnboardingView mine(Authentication auth) {
        return employees.mine(viewers.tenant(auth), viewers.email(auth));
    }

    @PatchMapping("/me/onboarding/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void toggleMine(Authentication auth, @PathVariable String taskId, @RequestBody TaskInput body) {
        employees.toggleMine(viewers.tenant(auth), viewers.email(auth), taskId, body.value());
    }

    @GetMapping("/hire-sagas")
    Page<SagaView> sagas(Authentication auth, @RequestParam(required = false) String status) {
        viewers.requireOperator(auth);
        return employees.sagas(viewers.tenant(auth), status);
    }

    @PostMapping("/hire-sagas/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(Authentication auth, @PathVariable String id) {
        viewers.requireOperator(auth);
        sagas.retry(viewers.tenant(auth), id, viewers.viewer(auth));
    }

    @GetMapping("/me/referral-rewards")
    Page<RewardView> rewards(Authentication auth) {
        return employees.rewards(viewers.tenant(auth), viewers.viewer(auth).subject());
    }
}
