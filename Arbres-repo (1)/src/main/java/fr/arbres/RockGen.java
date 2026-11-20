package fr.arbres;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Rochers (pierre, cobble, andésite, pierre moussue) avec des filons de charbon, fer et or. */
final class RockGen {
    private RockGen() {}

    private static double hash(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31) * 0xBF58476D1CE4E5B9L;
        h ^= y * 0x94D049BB133111EBL;
        h = Long.rotateLeft(h, 27) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    /** Génère (éventuellement) un rocher dans ce chunk. Retourne le nombre de rochers posés. */
    static int rocks(Canvas cv, int chunkX, int chunkZ, long worldSeed, double chance) {
        Random r = new Random(TreeGen.mix(worldSeed * 31L + chunkX * 341873128712L + chunkZ * 132897987541L + 7919L));
        if (r.nextDouble() >= chance) return 0;
        int n = 1 + (r.nextDouble() < 0.2 ? 1 : 0);
        int made = 0;
        for (int i = 0; i < n; i++) {
            int x = chunkX * 16 + 3 + r.nextInt(10);
            int z = chunkZ * 16 + 3 + r.nextInt(10);
            if (one(cv, r, x, z, true, worldSeed)) {
                made++;
                // quelques cailloux autour
                int pebbles = 2 + r.nextInt(3);
                for (int k = 0; k < pebbles; k++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double d = 4.5 + r.nextDouble() * 3;
                    one(cv, r, x + (int) Math.round(Math.cos(a) * d), z + (int) Math.round(Math.sin(a) * d), false, worldSeed);
                }
            }
        }
        return made;
    }

    private static boolean one(Canvas cv, Random r, int x, int z, boolean big, long worldSeed) {
        int y = cv.topY(x, z);
        Material m = null;
        while (y > cv.minY()) {
            m = cv.type(x, y, z);
            if (m == null) return false;
            if (m.isAir() || Blocks.leaf(m) || Blocks.log(m) || Blocks.replaceable(m)) {
                y--;
                continue;
            }
            break;
        }
        if (m == null || !Blocks.ground(m)) return false;
        String biome = cv.biome(x, y, z);
        boolean forest = biome.contains("forest");
        if (!(forest || biome.contains("plains") || biome.contains("meadow"))) return false;
        Material above = cv.type(x, y + 1, z);
        if (above == null || !(above.isAir() || Blocks.replaceable(above))) return false;

        double rx = big ? 2.0 + r.nextDouble() * 2.6 : 0.9 + r.nextDouble() * 0.9;
        double rz = rx * (0.75 + r.nextDouble() * 0.5);
        double ry = rx * (0.55 + r.nextDouble() * 0.35);
        double cy = y + ry * 0.35;
        int ix = (int) Math.ceil(rx + 1), iz = (int) Math.ceil(rz + 1), iy = (int) Math.ceil(ry + 1);
        long seed = worldSeed ^ (x * 73856093L) ^ (z * 19349663L);
        Map<TreeGen.P, Material> blocks = new HashMap<>();
        int cyi = (int) Math.round(cy);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dy = -iy; dy <= iy; dy++) {
                for (int dz = -iz; dz <= iz; dz++) {
                    int bx = x + dx, by = cyi + dy, bz = z + dz;
                    if (by < y - 1) continue;
                    double ex = dx / rx, ey = (by - cy) / ry, ez = dz / rz;
                    double d = ex * ex + ey * ey + ez * ez;
                    double n = hash(seed, bx, by, bz);
                    if (d + (n - 0.5) * 0.6 > 1.0) continue;
                    blocks.put(new TreeGen.P(bx, by, bz), pick(hash(seed + 1, bx, by, bz), forest, r));
                }
            }
        }
        if (blocks.isEmpty()) return false;

        if (big) {
            List<TreeGen.P> exposed = new ArrayList<>();
            int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (TreeGen.P p : blocks.keySet()) {
                if (p.y() <= y) continue;
                for (int[] d : n6) {
                    if (!blocks.containsKey(new TreeGen.P(p.x() + d[0], p.y() + d[1], p.z() + d[2]))) {
                        exposed.add(p);
                        break;
                    }
                }
            }
            vein(blocks, exposed, r, Material.COAL_ORE, 0.75, 3 + r.nextInt(4));
            vein(blocks, exposed, r, Material.IRON_ORE, 0.45, 2 + r.nextInt(4));
            vein(blocks, exposed, r, Material.GOLD_ORE, 0.18, 1 + r.nextInt(3));
        }

        Map<Material, BlockData> cache = new HashMap<>();
        for (Map.Entry<TreeGen.P, Material> e : blocks.entrySet()) {
            TreeGen.P p = e.getKey();
            Material t = cv.type(p.x(), p.y(), p.z());
            if (t == null) continue;
            if (t.isAir() || Blocks.replaceable(t) || Blocks.ground(t)) {
                cv.set(p.x(), p.y(), p.z(), cache.computeIfAbsent(e.getValue(), Material::createBlockData));
            }
        }
        return true;
    }

    private static Material pick(double u, boolean forest, Random r) {
        if (u < 0.52) return Material.STONE;
        if (u < 0.72) return Material.COBBLESTONE;
        if (u < 0.82) return Material.ANDESITE;
        if (u < (forest ? 0.95 : 0.90)) return Material.MOSSY_COBBLESTONE;
        return r.nextBoolean() ? Material.GRANITE : Material.DIORITE;
    }

    private static void vein(Map<TreeGen.P, Material> blocks, List<TreeGen.P> exposed, Random r,
                             Material ore, double chance, int size) {
        if (exposed.isEmpty() || r.nextDouble() >= chance) return;
        TreeGen.P cur = exposed.get(r.nextInt(exposed.size()));
        int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = 0; i < size; i++) {
            blocks.put(cur, ore);
            TreeGen.P next = null;
            for (int tries = 0; tries < 8 && next == null; tries++) {
                int[] d = n6[r.nextInt(6)];
                TreeGen.P cand = new TreeGen.P(cur.x() + d[0], cur.y() + d[1], cur.z() + d[2]);
                if (blocks.containsKey(cand)) next = cand;
            }
            if (next == null) return;
            cur = next;
        }
    }
}
