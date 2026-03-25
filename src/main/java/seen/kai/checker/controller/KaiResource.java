package seen.kai.checker.controller;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import seen.kai.checker.service.KaiService;

import java.util.concurrent.CompletableFuture;

@Path("/check")
@Tag(name = "Check", description = "Operasi pengecekan tiket secara manual")
public class KaiResource {
    @Inject
    KaiService kaiService;

    @GET
    @Operation(summary = "Picu pengecekan tiket", description = "Menjalankan proses pengecekan tiket secara asinkron untuk semua langganan.")
    public String check() {
        CompletableFuture.runAsync(() -> kaiService.checkTicketFromDatabase(""));
        return "Ticket check executed";
    }
}
