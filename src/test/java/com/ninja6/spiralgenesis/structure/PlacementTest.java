package com.ninja6.spiralgenesis.structure;

import org.bukkit.Material;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import com.ninja6.spiralgenesis.config.PluginConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Template geometry, and the two templates shipped in the jar. */
class PlacementTest {

    private static StructureSettings settings(int x, int y, int z) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("structure.offset.x", x);
        yaml.set("structure.offset.y", y);
        yaml.set("structure.offset.z", z);
        return new PluginConfig(yaml).getStructure();
    }

    @Test
    @DisplayName("quarter turns match vanilla's transform with no pivot")
    void rotations() {
        assertArrayEquals(new int[] {2, 5}, Placement.rotate(2, 5, StructureRotation.NONE));
        assertArrayEquals(new int[] {-5, 2}, Placement.rotate(2, 5, StructureRotation.CLOCKWISE_90));
        assertArrayEquals(new int[] {-2, -5}, Placement.rotate(2, 5, StructureRotation.CLOCKWISE_180));
        assertArrayEquals(new int[] {5, -2}, Placement.rotate(2, 5, StructureRotation.COUNTERCLOCKWISE_90));
    }

    @Test
    @DisplayName("the offset block lands on the spawn, whichever way the template is turned")
    void offsetLandsOnTheSpawn() {
        TestTemplate marker = new TestTemplate("t", List.of(
                new Template.Block(3, 1, 4, Material.GOLD_BLOCK, false),
                new Template.Block(0, 0, 0, Material.STONE, false)));
        for (StructureRotation rotation : StructureRotation.values()) {
            Placement placement = Placement.of(marker, settings(3, 1, 4), 100, 64, -40, rotation);
            Placement.WorldBlock spot = placement.blocks().get(0);
            assertEquals(100, spot.x(), rotation.name());
            assertEquals(64, spot.y(), rotation.name());
            assertEquals(-40, spot.z(), rotation.name());
            assertEquals(63, placement.originY(), "the floor is the layer below the feet");
            assertTrue(placement.blocks().get(1).floor());
        }
    }

    @Test
    @DisplayName("FACING_CENTRE turns the west side toward the centre")
    void facing() {
        assertEquals(StructureRotation.NONE, Placement.facing(0, 0, -100, 10));
        assertEquals(StructureRotation.CLOCKWISE_180, Placement.facing(0, 0, 100, 10));
        assertEquals(StructureRotation.CLOCKWISE_90, Placement.facing(0, 0, 10, -100));
        assertEquals(StructureRotation.COUNTERCLOCKWISE_90, Placement.facing(0, 0, 10, 100));
        assertEquals(StructureRotation.NONE, Placement.facing(5, 5, 5, 5));
        // The turned west side, (-1, 0), must point at the centre.
        for (int[] toward : new int[][] {{-9, 0}, {9, 0}, {0, -9}, {0, 9}}) {
            int[] west = Placement.rotate(-1, 0, Placement.facing(0, 0, toward[0], toward[1]));
            assertEquals(Integer.signum(toward[0]), west[0]);
            assertEquals(Integer.signum(toward[1]), west[1]);
        }
    }

    @Test
    @DisplayName("the shipped hut fits size 9 and the shipped tower needs 11 at their spawn spots")
    void shippedTemplates() throws IOException {
        NbtTemplate hut = NbtTemplate.read("spawn-hut.nbt");
        assertEquals(3700, hut.dataVersion, "saved for 1.20.4, the oldest supported version");
        assertArrayEquals(new int[] {7, 6, 7}, hut.size);
        assertTrue(hut.entities.isEmpty());
        assertEquals(7, Placement.requiredSize(hut, settings(3, 1, 3)));
        assertEquals(Material.AIR, hut.at(3, 1, 3), "the hut's spawn spot is clear");
        assertEquals(Material.AIR, hut.at(3, 2, 3));
        assertEquals(Material.COBBLESTONE, hut.at(3, 0, 3), "on its floor");

        NbtTemplate tower = NbtTemplate.read("spawn-tower.nbt");
        assertEquals(3700, tower.dataVersion);
        assertArrayEquals(new int[] {9, 19, 9}, tower.size);
        assertTrue(tower.entities.isEmpty());
        assertEquals(11, Placement.requiredSize(tower, settings(3, 1, 4)),
                "its spawn spot is a block off centre, so it reaches 5 blocks east");
        assertEquals(null, tower.at(3, 1, 4), "the tower's spawn spot is outside air");
        assertEquals(null, tower.at(3, 0, 4), "and keeps the natural ground under it");
        assertEquals(Material.LADDER, tower.at(4, 1, 4), "beside the ladder, to its east");
        assertEquals(198, tower.blocks().size());
        assertEquals(77, tower.blocks().stream().filter(Template.Block::air).count());
        assertEquals(Material.WALL_TORCH, tower.at(5, 4, 3), "the column's north torch");
        assertEquals(Material.WALL_TORCH, tower.at(5, 4, 5), "the column's south torch");

        NbtTemplate marker = NbtTemplate.read("spawn-marker.nbt");
        assertEquals(3700, marker.dataVersion);
        assertArrayEquals(new int[] {3, 7, 3}, marker.size);
        assertTrue(marker.entities.isEmpty());
        assertEquals(63, marker.blocks().size(), "every cell of the box is stored");
        assertEquals(29, marker.blocks().stream().filter(Template.Block::air).count(),
                "all air is kept, so terrain inside the open spiral is cleared");
        assertEquals(3, Placement.requiredSize(marker, settings(1, 1, 1)),
                "centred on the spawn, it fits the smallest claim");
        assertEquals(Material.COBBLESTONE, marker.at(1, 0, 1), "the marker's floor centre");
        assertEquals(Material.AIR, marker.at(1, 1, 1), "its spawn spot is clear");
        assertEquals(Material.AIR, marker.at(1, 2, 1));
        assertEquals(Material.TORCH, marker.at(1, 6, 1), "with a torch on top");
    }

    /** A structure file read with a minimal NBT reader, enough for these checks. */
    static final class NbtTemplate implements Template {
        int dataVersion;
        int[] size;
        List<Object> entities = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        final Map<String, Material> byPosition = new HashMap<>();

        static NbtTemplate read(String name) throws IOException {
            try (InputStream raw = PlacementTest.class.getResourceAsStream("/structures/" + name)) {
                assertNotNull(raw, name + " is shipped");
                DataInputStream in = new DataInputStream(new GZIPInputStream(raw));
                assertEquals(10, in.readByte());
                in.readUTF();
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) payload(in, 10);
                NbtTemplate template = new NbtTemplate();
                template.dataVersion = (Integer) root.get("DataVersion");
                @SuppressWarnings("unchecked")
                List<Object> size = (List<Object>) root.get("size");
                template.size = size.stream().mapToInt(v -> (Integer) v).toArray();
                @SuppressWarnings("unchecked")
                List<Object> entities = (List<Object>) root.get("entities");
                template.entities = entities;
                @SuppressWarnings("unchecked")
                List<Object> palette = (List<Object>) root.get("palette");
                @SuppressWarnings("unchecked")
                List<Object> blocks = (List<Object>) root.get("blocks");
                for (Object entry : blocks) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) entry;
                    @SuppressWarnings("unchecked")
                    List<Object> pos = (List<Object>) block.get("pos");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> state = (Map<String, Object>) palette.get((Integer) block.get("state"));
                    Material type = Material.matchMaterial((String) state.get("Name"));
                    assertNotNull(type, "every block exists: " + state.get("Name"));
                    int x = (Integer) pos.get(0);
                    int y = (Integer) pos.get(1);
                    int z = (Integer) pos.get(2);
                    template.blocks.add(new Block(x, y, z, type, type.isAir()));
                    template.byPosition.put(x + "," + y + "," + z, type);
                }
                return template;
            }
        }

        Material at(int x, int y, int z) {
            return byPosition.get(x + "," + y + "," + z);
        }

        private static Object payload(DataInputStream in, int type) throws IOException {
            switch (type) {
                case 1: return in.readByte();
                case 2: return in.readShort();
                case 3: return in.readInt();
                case 4: return in.readLong();
                case 5: return in.readFloat();
                case 6: return in.readDouble();
                case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
                case 8: return in.readUTF();
                case 9: {
                    int element = in.readByte();
                    int length = in.readInt();
                    List<Object> list = new ArrayList<>();
                    for (int i = 0; i < length; i++) {
                        list.add(payload(in, element));
                    }
                    return list;
                }
                case 10: {
                    Map<String, Object> map = new HashMap<>();
                    for (int tag = in.readByte(); tag != 0; tag = in.readByte()) {
                        String key = in.readUTF();
                        map.put(key, payload(in, tag));
                    }
                    return map;
                }
                case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
                case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
                default: throw new IOException("unknown NBT tag " + type);
            }
        }

        @Override
        public String name() {
            return "nbt";
        }

        @Override
        public List<Block> blocks() {
            return blocks;
        }

        @Override
        public void place(org.bukkit.Location origin, StructureRotation rotation, boolean includeEntities) {
            throw new UnsupportedOperationException();
        }
    }
}
