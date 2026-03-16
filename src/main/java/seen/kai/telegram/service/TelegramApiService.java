package seen.kai.telegram.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@ApplicationScoped
public class TelegramApiService {
    private static final Logger LOG = Logger.getLogger(TelegramApiService.class);

    @ConfigProperty(name = "kai.telegram.bot-token", defaultValue = "")
    String botToken;

    private TelegramClient telegramClient;

    @PostConstruct
    void init() {
        if (botToken == null || botToken.isBlank()) {
            LOG.warn("Telegram bot token belum diatur. Set kai.telegram.bot-token.");
            telegramClient = null;
            return;
        }
        telegramClient = new OkHttpTelegramClient(botToken.trim());
    }

    public boolean isEnabled() {
        return telegramClient != null;
    }

    public void sendMessage(String chatId, String text, InlineKeyboardMarkup markup) {
        if (telegramClient == null || chatId == null || chatId.isBlank() || text == null) {
            return;
        }
        try {
            SendMessage sendMessage = new SendMessage(chatId, text);
            if (markup != null) {
                sendMessage.setReplyMarkup(markup);
            }
            telegramClient.execute(sendMessage);
        } catch (TelegramApiException e) {
            LOG.errorf(e, "Failed to send Telegram message to chat_id=%s", chatId);
        }
    }

    public void editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        if (telegramClient == null || chatId == null || chatId.isBlank() || text == null) {
            return;
        }
        if (messageId == null || messageId <= 0) {
            sendMessage(chatId, text, markup);
            return;
        }
        try {
            EditMessageText editMessageText = new EditMessageText(text);
            editMessageText.setChatId(chatId);
            editMessageText.setMessageId(messageId);
            if (markup != null) {
                editMessageText.setReplyMarkup(markup);
            }
            telegramClient.execute(editMessageText);
        } catch (TelegramApiException e) {
            LOG.errorf(e, "Failed to edit Telegram message chat_id=%s message_id=%d", chatId, messageId);
            sendMessage(chatId, text, markup);
        }
    }

    public void answerCallback(String callbackId) {
        if (telegramClient == null || callbackId == null || callbackId.isBlank()) {
            return;
        }
        try {
            telegramClient.execute(new AnswerCallbackQuery(callbackId));
        } catch (TelegramApiException e) {
            LOG.warnf(e, "Failed to answer callback_id=%s", callbackId);
        }
    }
}

