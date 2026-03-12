import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/check")
public class KaiResource {
    @Inject
    KaiService kaiService;

    @GET
    public String check() {
        kaiService.checkTicket();
        return "Ticket check executed";
    }
}
