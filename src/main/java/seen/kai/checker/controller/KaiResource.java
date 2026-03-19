package seen.kai.checker.controller;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import seen.kai.checker.service.KaiService;

import java.util.concurrent.CompletableFuture;

@Path("/check")
public class KaiResource {
    @Inject
    KaiService kaiService;

    @GET
    public String check() {
        CompletableFuture.runAsync(() -> kaiService.checkTicketFromDatabase(""));
        return "Ticket check executed";
    }
}
