package com.example.museum.mock;

import com.example.museum.api.BuyMuseumTicketsRequest;
import com.example.museum.api.BuyMuseumTicketsResponse;
import com.example.museum.api.GetTicketCodeRequest;
import com.example.museum.api.GetTicketCodeResponse;
import com.example.museum.api.TicketsApi;
import com.example.museum.model.Error;
import com.example.museum.model.MuseumTicketsConfirmation;
import com.example.museum.model.TicketType;
import dev.openapifn.spring.FilePart;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Mock ticket sales: every purchase succeeds (event tickets need a known event) and gets a QR code. */
@Component
public class TicketOffice implements TicketsApi {

    /** A 1×1 PNG standing in for the QR code. */
    static final byte[] QR_CODE = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private final Set<UUID> sold = ConcurrentHashMap.newKeySet();

    @Override
    public BuyMuseumTicketsResponse buyMuseumTickets(BuyMuseumTicketsRequest request) {
        var ticket = request.body();
        if (ticket.ticketType() == TicketType.EVENT && ticket.eventId() == null) {
            return BuyMuseumTicketsResponse.badRequest(new Error("object", "Event tickets need an eventId"));
        }
        var id = UUID.randomUUID();
        sold.add(id);
        var what = ticket.ticketType() == TicketType.EVENT ? "event" : "general entry";
        return BuyMuseumTicketsResponse.created(new MuseumTicketsConfirmation(id, ticket.ticketDate(), ticket.ticketType(), ticket.eventId(),
            "Museum " + what + " ticket for " + ticket.ticketDate() + " was successfully purchased", "ticket-" + id.toString().substring(0, 8)));
    }

    @Override
    public GetTicketCodeResponse getTicketCode(GetTicketCodeRequest request) {
        return sold.contains(request.ticketId())
            ? GetTicketCodeResponse.ok(FilePart.of("ticket.png", "image/png", QR_CODE))
            : GetTicketCodeResponse.notFound(new Error("object", "Ticket not found"));
    }
}
