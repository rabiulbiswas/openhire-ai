package com.openhire.employee.domain;

import com.openhire.employee.domain.EmployeeTypes.TemplateTask;
import java.util.List;

/** The checklist every new hire starts with; task ids stay stable so progress survives template edits. */
public final class OnboardingTemplate {
    private OnboardingTemplate() {}

    public static final String DOCUMENTS = "Documents";
    public static final String IT_ACCESS = "IT access";
    public static final String BUDDY_AND_HR = "Buddy and HR";
    public static final String FIRST_WEEK = "First week";

    public static List<TemplateTask> standard() {
        return List.of(
                new TemplateTask("sign-agreement", "Sign the employment agreement", DOCUMENTS),
                new TemplateTask("id-proof", "Upload a government ID", DOCUMENTS),
                new TemplateTask("bank-details", "Submit bank details for payroll", DOCUMENTS),
                new TemplateTask("education-proof", "Upload education certificates", DOCUMENTS),
                new TemplateTask("laptop", "Collect your laptop and accessories", IT_ACCESS),
                new TemplateTask("accounts", "Activate your email and single sign-on account", IT_ACCESS),
                new TemplateTask("team-tools", "Get access to your team's tools", IT_ACCESS),
                new TemplateTask("meet-buddy", "Meet your onboarding buddy", BUDDY_AND_HR),
                new TemplateTask("hr-call", "Join the HR welcome call", BUDDY_AND_HR),
                new TemplateTask("policies", "Read the company policies", BUDDY_AND_HR),
                new TemplateTask("security-training", "Complete security awareness training", FIRST_WEEK),
                new TemplateTask("goals", "Agree 30-60-90 day goals with your manager", FIRST_WEEK),
                new TemplateTask("team-intro", "Introduce yourself to the team", FIRST_WEEK));
    }
}
