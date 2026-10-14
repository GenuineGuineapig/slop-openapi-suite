package com.example.train.mock;

import com.example.train.api.GetStationsRequest;
import com.example.train.api.GetStationsResponse;
import com.example.train.api.QueryStationsRequest;
import com.example.train.api.QueryStationsResponse;
import com.example.train.api.StationsApi;
import com.example.train.model.Station;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Mock station search over {@link Timetable#STATIONS}. */
@Component
public class MockStations implements StationsApi {

    /** Deprecated in the API description in favour of {@link #queryStations}, but still served. */
    @Override
    @Deprecated
    public Mono<GetStationsResponse> getStations(GetStationsRequest request) {
        var page = page(search(request.search(), request.country()), request.page(), request.limit());
        return Mono.just(GetStationsResponse.ok(new GetStationsResponse.OkBody(page,
            new GetStationsResponse.OkBody.Links(URI.create(Timetable.BASE + "/stations?page=" + number(request.page())), null, null)), "max-age=3600", null, null));
    }

    @Override
    public Mono<QueryStationsResponse> queryStations(QueryStationsRequest request) {
        var query = request.body();
        var matching = query == null ? Timetable.STATIONS : search(query.search(), query.country());
        return Mono.just(QueryStationsResponse.ok(new QueryStationsResponse.OkBody(page(matching, request.page(), request.limit()),
            new QueryStationsResponse.OkBody.Links(URI.create(Timetable.BASE + "/stations"), null, null)), "no-store", null));
    }

    static List<Station> search(String text, String country) {
        return Timetable.STATIONS.stream()
            .filter(station -> text == null || (station.name() + " " + station.address()).toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))
            .filter(station -> country == null || station.countryCode().equalsIgnoreCase(country))
            .toList();
    }

    static <T> List<T> page(List<T> items, Integer page, Integer limit) {
        var size = limit == null ? 10 : limit;
        return items.stream().skip((long) (number(page) - 1) * size).limit(size).toList();
    }

    static int number(Integer page) {
        return page == null ? 1 : page;
    }
}
