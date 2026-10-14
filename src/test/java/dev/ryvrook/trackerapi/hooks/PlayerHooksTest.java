package dev.ryvrook.trackerapi.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerHooksTest {

    @Test
    void commandIsTheFirstWordWithoutTheSlash() {
        assertEquals("spawn", PlayerHooks.commandOf("/spawn"));
        assertEquals("tp", PlayerHooks.commandOf("/TP Steve Alex"));
        assertEquals("trackerapi", PlayerHooks.commandOf("/trackerapi\tstatus"));
    }

    @Test
    void plainChatAndEmptyCommandsAreNotCommands() {
        assertNull(PlayerHooks.commandOf("hello"));
        assertNull(PlayerHooks.commandOf("/"));
        assertNull(PlayerHooks.commandOf("/ spawn"));
        assertNull(PlayerHooks.commandOf(null));
        assertNull(PlayerHooks.commandOf("/" + "x".repeat(65)));
    }
}
