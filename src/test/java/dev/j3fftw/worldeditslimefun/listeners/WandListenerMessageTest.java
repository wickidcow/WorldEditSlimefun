package dev.j3fftw.worldeditslimefun.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Preserves the exact denial text and red presentation without legacy ChatColor. */
class WandListenerMessageTest {
    @Test
    void denialTextAndColorMatchTheHistoricalPlayerMessage() {
        Component message = WandListener.permissionDeniedMessage();
        assertEquals("You don't have permission!",
                PlainTextComponentSerializer.plainText().serialize(message));
        assertEquals(NamedTextColor.RED, message.color());
        assertTrue(message.children().isEmpty());
    }
}
