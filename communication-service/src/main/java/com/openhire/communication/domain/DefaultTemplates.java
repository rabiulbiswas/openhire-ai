package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.Channel;
import java.util.List;

/** Starting templates every tenant receives; tenants edit them into new versions. */
public final class DefaultTemplates {
    public record Seed(String key, String name, List<Channel> channels, String subject, String body) {}

    private static final List<Channel> EMAIL_WHATSAPP = List.of(Channel.EMAIL, Channel.WHATSAPP);

    public static final List<Seed> ALL = List.of(
            new Seed(
                    "application-received",
                    "Application received",
                    EMAIL_WHATSAPP,
                    "We received your application for {{job.title}}",
                    "Hi {{candidate.firstName}},\n\nThank you for applying for the {{job.title}} role at {{company.name}}. "
                            + "We will review your application and get back to you.\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "scheduling-link",
                    "Choose your interview time",
                    EMAIL_WHATSAPP,
                    "Choose a time for your {{round.name}} interview",
                    "Hi {{candidate.firstName}},\n\nPlease choose a time for your {{round.name}} interview for the {{job.title}} role:\n"
                            + "{{booking.url}}\n\nThe times shown are held for you for a short while.\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "interview-invitation",
                    "Interview invitation",
                    EMAIL_WHATSAPP,
                    "Your {{round.name}} interview for {{job.title}}",
                    "Hi {{candidate.firstName}},\n\nYour {{round.name}} interview is booked for {{round.start}} ({{round.timezone}}).\n"
                            + "{{meeting.url}}\n\nReply to this message if you need to change the time.\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "interview-rescheduled",
                    "Interview rescheduled",
                    EMAIL_WHATSAPP,
                    "New time for your {{round.name}} interview",
                    "Hi {{candidate.firstName}},\n\nYour {{round.name}} interview for {{job.title}} has moved to {{round.start}} "
                            + "({{round.timezone}}).\n{{meeting.url}}\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "interview-cancelled",
                    "Interview cancelled",
                    EMAIL_WHATSAPP,
                    "Your {{round.name}} interview has been cancelled",
                    "Hi {{candidate.firstName}},\n\nWe have cancelled your {{round.name}} interview for {{job.title}}. "
                            + "We will be in touch about next steps.\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "interview-reminder",
                    "Interview reminder",
                    EMAIL_WHATSAPP,
                    "Reminder: {{round.name}} interview",
                    "Hi {{candidate.firstName}},\n\nA reminder that your {{round.name}} interview for {{job.title}} is at "
                            + "{{round.start}} ({{round.timezone}}).\n{{meeting.url}}\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "offer-ready",
                    "Offer ready",
                    List.of(Channel.EMAIL),
                    "Your offer for {{job.title}}",
                    "Hi {{candidate.firstName}},\n\nYour offer for the {{job.title}} role is ready to review in the candidate portal.\n\n"
                            + "Best,\n{{recruiter.name}}"),
            new Seed(
                    "not-moving-forward",
                    "Not moving forward",
                    List.of(Channel.EMAIL),
                    "Your application for {{job.title}}",
                    "Hi {{candidate.firstName}},\n\nThank you for your time with us for the {{job.title}} role. After careful review, "
                            + "we have decided to move forward with other candidates.\n\nBest,\n{{recruiter.name}}"),
            new Seed(
                    "talent-reengagement",
                    "Talent pool re-engagement",
                    EMAIL_WHATSAPP,
                    "A new role at {{company.name}}",
                    "Hi {{candidate.firstName}},\n\nWe have a new {{job.title}} role that may interest you. "
                            + "Reply if you would like to hear more.\n\nBest,\n{{recruiter.name}}"),
            new Seed(
                    "offer-sent",
                    "Offer sent",
                    List.of(Channel.EMAIL),
                    "Your offer for {{job.title}}",
                    "Hi {{candidate.firstName}},\n\nWe are delighted to share your offer for the {{job.title}} role at {{company.name}}. "
                            + "Please review it and respond by {{offer.expiryDate}}:\n{{offer.url}}\n\n"
                            + "You can accept, decline or ask for changes from that page.\n\nBest,\nThe {{company.name}} hiring team"),
            new Seed(
                    "offer-accepted",
                    "Offer accepted",
                    List.of(Channel.EMAIL),
                    "Welcome to {{company.name}}",
                    "Hi {{candidate.firstName}},\n\nThank you for accepting the {{job.title}} role. We are looking forward to you joining on "
                            + "{{offer.joiningDate}}. We will be in touch shortly about onboarding.\n\nBest,\nThe {{company.name}} hiring team"));

    private DefaultTemplates() {}
}
