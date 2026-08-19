/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit;

import com.sk89q.wepif.PermissionsResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BukkitPlayerPermissionEvaluatorTest {

    private static final String PERMISSION = "worldedit.selection.pos";

    @Test
    void usesBukkitPermissionStateOutsideOwningRegion() {
        Player player = mock(Player.class);
        PermissionsResolver resolver = mock(PermissionsResolver.class);
        when(player.hasPermission(PERMISSION)).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);

            assertTrue(BukkitPlayerPermissionEvaluator.hasPermission(player, PERMISSION, resolver, false));
        }

        verify(player).hasPermission(PERMISSION);
        verify(player, never()).getWorld();
        verifyNoInteractions(resolver);
    }

    @Test
    void usesWorldAwareResolverOnOwningRegion() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        PermissionsResolver resolver = mock(PermissionsResolver.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(resolver.hasPermission("world", player, PERMISSION)).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);

            assertTrue(BukkitPlayerPermissionEvaluator.hasPermission(player, PERMISSION, resolver, false));
        }

        verify(resolver).hasPermission("world", player, PERMISSION);
        verify(player, never()).hasPermission(PERMISSION);
    }

    @Test
    void preservesOperatorBypass() {
        Player player = mock(Player.class);
        PermissionsResolver resolver = mock(PermissionsResolver.class);
        when(player.isOp()).thenReturn(true);

        assertTrue(BukkitPlayerPermissionEvaluator.hasPermission(player, PERMISSION, resolver, false));

        verify(player, never()).hasPermission(PERMISSION);
        verify(player, never()).getWorld();
        verifyNoInteractions(resolver);
    }

    @Test
    void respectsDisabledOperatorBypass() {
        Player player = mock(Player.class);
        PermissionsResolver resolver = mock(PermissionsResolver.class);
        when(player.isOp()).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);

            assertFalse(BukkitPlayerPermissionEvaluator.hasPermission(player, PERMISSION, resolver, true));
        }

        verify(player).hasPermission(PERMISSION);
    }
}
