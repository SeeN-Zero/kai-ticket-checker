package seen.kai.telegram.controller;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.telegram.telegrambots.meta.api.objects.Update;
import seen.kai.telegram.service.TelegramBotService;

@Path("/telegram/webhook")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TelegramWebhookController {
    private final TelegramBotService telegramBotService;

    public TelegramWebhookController(TelegramBotService telegramBotService) {
        this.telegramBotService = telegramBotService;
    }

    @POST
    public Response webhook(Update update) {
        telegramBotService.handleUpdate(update);
        return Response.ok().build();
    }
}
