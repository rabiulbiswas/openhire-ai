package com.openhire.offer.application;

import java.time.Duration;
import java.time.ZoneId;

public record OfferSettings(
        int defaultExpiryDays, Duration tokenGrace, String portalUrl, String companyName, ZoneId zone) {}
