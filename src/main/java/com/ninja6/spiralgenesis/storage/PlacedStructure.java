package com.ninja6.spiralgenesis.storage;

/**
 * A structure placed on a spawn plot, recorded so it is never placed there again.
 *
 * <p>Stored under the top-level {@code structures} key of {@code data.yml}, by spiral centre
 * and index, apart from the player records: a plot keeps its structure when it is
 * released or its player is reassigned. Versions up to 1.0.0-beta.1 do not read the key
 * and keep it unchanged when they save, since they write the loaded file back whole.
 *
 * @param file     the template's file name when it was placed
 * @param world    the world it was placed in
 * @param x        world X of the template's origin
 * @param y        world Y of the template's origin, its floor layer
 * @param z        world Z of the template's origin
 * @param rotation the quarter turn it was placed with
 * @param placedAt when it was placed, as an ISO-8601 instant
 * @param player   the name of the player whose plot it was placed for
 */
public record PlacedStructure(String file, String world, int x, int y, int z, String rotation,
                              String placedAt, String player) { }
