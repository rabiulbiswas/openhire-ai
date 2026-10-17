package com.openhire.applicationservice.domain;

public enum ApplicationStage {
    APPLIED,
    SHORTLISTED,
    REJECTED,
    WITHDRAWN;

    public void requireTransitionTo(ApplicationStage next) {
        boolean allowed =
                switch (this) {
                    case APPLIED -> next == SHORTLISTED || next == REJECTED || next == WITHDRAWN;
                    case SHORTLISTED -> next == REJECTED || next == WITHDRAWN;
                    case REJECTED, WITHDRAWN -> false;
                };
        if (!allowed) throw new IllegalStateException("Cannot move an application from " + this + " to " + next);
    }
}
