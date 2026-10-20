package com.ninja6.spiralgenesis.messages;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalHintTest {
    private WorldMock world;

    @BeforeEach void setUp() { world = MockBukkit.mock().addSimpleWorld("world"); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    @Test void rotationAndVerticalMovementAreNotMovement() {
        ArrivalHint hint = new ArrivalHint(at(-0.5, 64, -12.5, 0, 0), 0, 30);
        assertTrue(hint.refresh(at(-0.5, 64, -12.5, 0, 0)));
        assertTrue(hint.refresh(at(-0.5, 64, -12.5, 180, -90)));
        assertTrue(hint.refresh(at(-0.9, 80, -12.1, 90, 45)));
        // Negative coordinates: -0.9 and -0.1 are both block -1.
        assertTrue(hint.refresh(at(-0.1, 63, -12.9, 0, 0)));
        assertFalse(hint.refresh(at(0.1, 64, -12.5, 0, 0)), "linger 0 stops on the first move");
    }

    @Test void lingerCountsFromTheFirstMoveOnly() {
        ArrivalHint hint = new ArrivalHint(at(10.5, 64, 10.5, 0, 0), 2, 30);
        assertTrue(hint.refresh(at(10.5, 64, 10.5, 0, 0)));
        assertTrue(hint.refresh(at(10.5, 64, 11.5, 0, 0)));
        assertTrue(hint.refresh(at(10.5, 64, 10.5, 0, 0)));
        assertFalse(hint.refresh(at(10.5, 64, 10.5, 0, 0)));
        assertFalse(hint.refresh(at(10.5, 64, 10.5, 0, 0)), "an ended hint stays ended");
    }

    @Test void capAppliesWhetherOrNotThePlayerMoves() {
        ArrivalHint still = new ArrivalHint(at(10.5, 64, 10.5, 0, 0), 5, 3);
        for (int i = 0; i < 3; i++) {
            assertTrue(still.refresh(at(10.5, 64, 10.5, i * 10f, 0)));
        }
        assertFalse(still.refresh(at(10.5, 64, 10.5, 0, 0)));

        ArrivalHint moving = new ArrivalHint(at(10.5, 64, 10.5, 0, 0), 5, 2);
        assertTrue(moving.refresh(at(20.5, 64, 10.5, 0, 0)));
        assertTrue(moving.refresh(at(20.5, 64, 10.5, 0, 0)));
        assertFalse(moving.refresh(at(20.5, 64, 10.5, 0, 0)), "the cap cuts the linger short");

        assertFalse(new ArrivalHint(at(0, 64, 0, 0, 0), 5, 0).refresh(at(0, 64, 0, 0, 0)));
    }

    @Test void anotherWorldEndsIt() {
        ArrivalHint hint = new ArrivalHint(at(10.5, 64, 10.5, 0, 0), 5, 30);
        WorldMock other = new WorldMock();
        other.setName("other");
        assertFalse(hint.refresh(new Location(other, 10.5, 64, 10.5)));
        assertFalse(hint.refresh(at(10.5, 64, 10.5, 0, 0)));
    }
}
