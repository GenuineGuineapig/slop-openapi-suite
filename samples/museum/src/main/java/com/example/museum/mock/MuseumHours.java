package com.example.museum.mock;

import com.example.museum.api.GetMuseumHoursRequest;
import com.example.museum.api.GetMuseumHoursResponse;
import com.example.museum.api.OperationsApi;
import com.example.museum.model.MuseumDailyHours;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Mock opening hours: 09:00–18:00, closed on Mondays, longer on Fridays. */
@Component
public class MuseumHours implements OperationsApi {

    static final LocalDate TODAY = LocalDate.of(2025, 6, 2);

    @Override
    public GetMuseumHoursResponse getMuseumHours(GetMuseumHoursRequest request) {
        var start = request.startDate() != null ? request.startDate() : TODAY;
        var limit = request.limit() != null ? request.limit() : 10;
        var page = request.page() != null ? request.page() : 1;
        var hours = Stream.iterate(start, day -> day.plusDays(1))
            .filter(day -> day.getDayOfWeek() != DayOfWeek.MONDAY)
            .skip((long) (page - 1) * limit)
            .limit(limit)
            .map(day -> new MuseumDailyHours(day, "09:00", day.getDayOfWeek() == DayOfWeek.FRIDAY ? "21:00" : "18:00"))
            .toList();
        return GetMuseumHoursResponse.ok(hours);
    }
}
