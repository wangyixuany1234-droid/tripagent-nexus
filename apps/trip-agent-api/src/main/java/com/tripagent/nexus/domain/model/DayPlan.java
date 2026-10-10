package com.tripagent.nexus.domain.model;

import java.time.LocalDate;
import java.util.List;

public record DayPlan(int dayNumber, LocalDate date, String title, List<ItineraryItem> items) {}
