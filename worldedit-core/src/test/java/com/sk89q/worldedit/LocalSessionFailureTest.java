package com.sk89q.worldedit;

import com.fastasyncworldedit.core.configuration.Settings;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Isolated
class LocalSessionFailureTest {

    @Test
    void preservesPartialHistoryWhileReportingFlushFailure() {
        Settings.HISTORY previous = Settings.settings().HISTORY;
        Settings.settings().HISTORY = new Settings.HISTORY();
        Settings.settings().HISTORY.USE_DISK = false;
        try {
            EditSession edit = mock(EditSession.class);
            ChangeSet changes = mock(ChangeSet.class);
            when(edit.getChangeSet()).thenReturn(changes);
            when(changes.isEmpty()).thenReturn(false);
            when(changes.longSize()).thenReturn(1L);
            RuntimeException failure = new IllegalStateException("Chunk write failed");
            doThrow(failure).when(edit).flushQueue();
            LocalSession session = new LocalSession();

            assertSame(failure, assertThrows(RuntimeException.class, () -> session.remember(edit, true, 10)));
            assertEquals(1, session.getHistory().size());
            assertSame(changes, session.getHistory().getFirst());
        } finally {
            Settings.settings().HISTORY = previous;
        }
    }
}
