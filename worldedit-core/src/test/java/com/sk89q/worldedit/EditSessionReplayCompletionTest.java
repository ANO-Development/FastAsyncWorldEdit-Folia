package com.sk89q.worldedit;

import com.fastasyncworldedit.core.limit.FaweLimit;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class EditSessionReplayCompletionTest {
    @Test
    void replayFlushesDestinationInsteadOfCancelledSource() throws Exception {
        EditSession source = mock(EditSession.class);
        EditSession destination = mock(EditSession.class);
        ChangeSet history = mock(ChangeSet.class);
        when(source.getChangeSet()).thenReturn(history);
        when(history.backwardIterator()).thenReturn(Collections.emptyIterator());
        when(history.forwardIterator()).thenReturn(Collections.emptyIterator());
        when(destination.getLimit()).thenReturn(new FaweLimit());
        var bypass = EditSession.class.getDeclaredField("bypassAll");
        bypass.setAccessible(true);
        bypass.set(destination, mock(Extent.class));
        doThrow(new IllegalStateException("Source edit was cancelled")).when(source).flushQueue();
        doCallRealMethod().when(source).undo(destination);
        doCallRealMethod().when(source).redo(destination);

        assertDoesNotThrow(() -> source.undo(destination));
        assertDoesNotThrow(() -> source.redo(destination));
        verify(destination, times(2)).flushQueue();
        verify(source, never()).flushQueue();
    }
}
