package seen.kai.subscription.controller;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import seen.kai.subscription.dto.request.SubscriptionRequest;
import seen.kai.subscription.dto.response.SubscriptionResponse;
import seen.kai.subscription.service.SubscriptionService;

@Path("/subscriptions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Subscription", description = "Operasi terkait langganan tiket")
public class SubscriptionController {
    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @POST
    @Operation(summary = "Buat atau lampirkan langganan", description = "Membuat langganan baru atau melampirkan chat ID ke langganan yang sudah ada berdasarkan rute dan harga.")
    public Response create(SubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.createOrAttach(request);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }
}
