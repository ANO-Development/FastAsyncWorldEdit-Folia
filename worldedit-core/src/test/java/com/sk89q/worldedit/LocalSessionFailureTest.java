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

    @Test
    void remembersCancelledEditThatNeverCountedItsChanges() {
        Settings.HISTORY previous = Settings.settings().HISTORY;
        Settings.settings().HISTORY = new Settings.HISTORY();
        Settings.settings().HISTORY.USE_DISK = false;
        try {
            // //cancel stops a parallel edit before it sets its change count, but the chunks it
            // already wrote are in the change set.
            EditSession edit = mock(EditSession.class);
            ChangeSet changes = mock(ChangeSet.class);
            when(edit.size()).thenReturn(0);
            when(edit.getChangeSet()).thenReturn(changes);
            when(changes.isEmpty()).thenReturn(false);
            when(changes.longSize()).thenReturn(1L);
            LocalSession session = new LocalSession();

            session.remember(edit);

            assertEquals(1, session.getHistory().size());
            assertSame(changes, session.getHistory().getFirst());
        } finally {
            Settings.settings().HISTORY = previous;
        }
    }

    @Test
    void skipsEditThatChangedNothing() {
        EditSession edit = mock(EditSession.class);
        ChangeSet changes = mock(ChangeSet.class);
        when(edit.size()).thenReturn(0);
        when(edit.getChangeSet()).thenReturn(changes);
        when(changes.isEmpty()).thenReturn(true);
        LocalSession session = new LocalSession();

        session.remember(edit);

        assertTrue(session.getHistory().isEmpty());
    }
}
