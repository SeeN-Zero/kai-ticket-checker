package seen.kai.subscription.controller;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import seen.kai.subscription.dto.request.SubscriptionRequest;
import seen.kai.subscription.dto.response.SubscriptionResponse;
import seen.kai.subscription.service.SubscriptionService;

@Path("/subscriptions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SubscriptionController {
    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @POST
    public Response create(SubscriptionRequest request) {
        SubscriptionResponse response = subscriptionService.createOrAttach(request);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }
}
