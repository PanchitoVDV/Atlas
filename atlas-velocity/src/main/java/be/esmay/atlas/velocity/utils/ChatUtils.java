package be.esmay.atlas.velocity.utils;

import lombok.experimental.PackagePrivate;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentBuilder;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public final class ChatUtils {

    public static Component format(String message, Object... args) {
        MiniMessage extendedInstance = MiniMessage.builder().build();
        return extendedInstance.deserialize(replaceArguments(message, args)).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @PackagePrivate
    private static String replaceArguments(String message, Object... replacements) {
        for (int i = 0; i < replacements.length; i++) {
            String placeholder = "%" + (i + 1);
            message = message.replaceAll(placeholder + "(?![0-9])", String.valueOf(replacements[i]));
        }
        return message;
    }

    public static Component formatLinks(String message, String chatColor) {
        ComponentBuilder<TextComponent, TextComponent.Builder> messageComponent = Component.empty().toBuilder();
        Pattern pattern = Pattern.compile("(http|https)://[\\w\\-.]+(:\\d+)?(/\\S*)?");
        Matcher matcher = pattern.matcher(message);

        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String beforeUrl = message.substring(lastEnd, matcher.start());
                messageComponent.append(Component.text(beforeUrl).style(ChatUtils.format(chatColor).style()));
            }

            String url = matcher.group();
            messageComponent.append(Component.text(url).style(ChatUtils.format(chatColor).style()).clickEvent(ClickEvent.openUrl(url)));
            lastEnd = matcher.end();
        }

        if (lastEnd < message.length()) {
            String remainingText = message.substring(lastEnd);
            messageComponent.append(Component.text(remainingText).style(ChatUtils.format(chatColor).style()));
        }

        return messageComponent.build();
    }

    @PackagePrivate
    private static String tint(String hexColor, double factor) {
        int red = Integer.parseInt(hexColor.substring(1, 3), 16);
        int green = Integer.parseInt(hexColor.substring(3, 5), 16);
        int blue = Integer.parseInt(hexColor.substring(5, 7), 16);

        red = (int) Math.round(Math.min(255, red + (255 - red) * factor));
        green = (int) Math.round(Math.min(255, green + (255 - green) * factor));
        blue = (int) Math.round(Math.min(255, blue + (255 - blue) * factor));

        return String.format("#%02x%02x%02x", red, green, blue);
    }
}