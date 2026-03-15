package seen.kai.checker.controller;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import seen.kai.checker.service.KaiService;

@Path("/check")
public class KaiResource {
    @Inject
    KaiService kaiService;

    @GET
    public String check() {
        kaiService.checkTicketFromDatabase();
        return "Ticket check executed";
    }
}
