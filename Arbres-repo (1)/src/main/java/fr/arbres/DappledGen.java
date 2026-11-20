package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import java.util.Random;

/**
 * Forêt tachetée (dappled forest, 26.3) : tapis de feuilles mortes sous les arbres, buissons rouges,
 * plaques de terre stérile, peupliers tombés, champignons. Les peupliers eux-mêmes sont refaits par TreeGen.
 */
final class DappledGen {
    private DappledGen() {}

    /** {y du sol, 1 si des feuilles au-dessus}, ou null. */
    private static int[] scan(Canvas cv, int x, int z) {
        int y = cv.topY(x, z);
        boolean leaves = false;
        while (y > cv.minY()) {
            Material m = cv.type(x, y, z);
            if (m == null) return null;
            if (Blocks.leaf(m)) {
                leaves = true;
                y--;
                continue;
            }
            if (m.isAir() || Blocks.log(m) || Blocks.replaceable(m)) {
                y--;
                continue;
            }
            if (Blocks.liquid(m)) return null;
            return new int[] {y, leaves ? 1 : 0};
        }
        return null;
    }

    private static boolean soil(Material m) {
        return m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.PODZOL;
    }

    private static BlockData mat(String name) {
        Material m = Material.getMaterial(name);
        return m == null ? null : m.createBlockData();
    }

    static int decorate(Canvas cv, int chunkX, int chunkZ, long seed, double chance) {
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        int[] c = scan(cv, x0 + 8, z0 + 8);
        if (c == null || !cv.biome(x0 + 8, c[0], z0 + 8).contains("dappled_forest")) return 0;
        Random r = new Random(TreeGen.mix(seed * 31L + chunkX * 341873128712L + chunkZ * 132897987541L + 6262L));
        if (r.nextDouble() >= chance) return 0;
        BlockData litter = mat("LEAF_LITTER");
        BlockData shrub = mat("RED_SHRUB");
        BlockData brown = mat("BROWN_MUSHROOM");
        BlockData coarse = Material.COARSE_DIRT.createBlockData();
        int made = 0;

        // tapis de feuilles : dense sous les feuillages, clairsemé ailleurs
        if (litter != null) {
            for (int i = 0; i < 70; i++) {
                int x = x0 + r.nextInt(16), z = z0 + r.nextInt(16);
                int[] s = scan(cv, x, z);
                if (s == null) continue;
                Material g = cv.type(x, s[0], z);
                if (g == null || !soil(g)) continue;
                Material a = cv.type(x, s[0] + 1, z);
                if (a == null || !(a.isAir() || Blocks.replaceable(a)) || Blocks.leaf(a)) continue;
                if (s[1] == 1 || r.nextDouble() < 0.2) {
                    cv.set(x, s[0] + 1, z, litter);
                    made++;
                }
            }
        }
        // plaques de terre stérile
        for (int p = 0; p < 2; p++) {
            int px = x0 + 2 + r.nextInt(12), pz = z0 + 2 + r.nextInt(12);
            double rad = 1.6 + r.nextDouble() * 1.6;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx * dx + dz * dz > rad * rad) continue;
                    int[] s = scan(cv, px + dx, pz + dz);
                    if (s == null) continue;
                    Material g = cv.type(px + dx, s[0], pz + dz);
                    if (g == Material.GRASS_BLOCK) cv.set(px + dx, s[0], pz + dz, coarse);
                }
            }
        }
        // buissons rouges par petits groupes
        if (shrub != null) {
            for (int p = 0; p < 3; p++) {
                int px = x0 + 2 + r.nextInt(12), pz = z0 + 2 + r.nextInt(12);
                int n = 3 + r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    int x = px + r.nextInt(5) - 2, z = pz + r.nextInt(5) - 2;
                    int[] s = scan(cv, x, z);
                    if (s == null) continue;
                    Material g = cv.type(x, s[0], z);
                    Material a = cv.type(x, s[0] + 1, z);
                    if (g == null || !soil(g) || a == null || !(a.isAir() || (Blocks.replaceable(a) && !Blocks.leaf(a)))) continue;
                    cv.set(x, s[0] + 1, z, shrub);
                    made++;
                }
            }
        }
        // peuplier tombé, entouré de feuilles et de champignons
        Material poplar = Material.getMaterial("POPLAR_LOG");
        if (poplar != null && r.nextDouble() < 0.4) {
            int len = 4 + r.nextInt(4);
            boolean alongX = r.nextBoolean();
            int sx = x0 + 2 + r.nextInt(8), sz = z0 + 2 + r.nextInt(8);
            BlockData log = poplar.createBlockData();
            if (log instanceof Orientable o) o.setAxis(alongX ? Axis.X : Axis.Z);
            int gy = Integer.MIN_VALUE;
            for (int k = 0; k < len; k++) {
                int x = sx + (alongX ? k : 0), z = sz + (alongX ? 0 : k);
                int[] s = scan(cv, x, z);
                if (s == null || s[1] == 1) break;
                Material g = cv.type(x, s[0], z);
                if (g == null || !soil(g)) break;
                if (gy != Integer.MIN_VALUE && Math.abs(s[0] - gy) > 1) break;
                gy = s[0];
                Material a = cv.type(x, s[0] + 1, z);
                if (a == null || !(a.isAir() || Blocks.replaceable(a))) break;
                cv.set(x, s[0] + 1, z, log);
                made++;
                if (brown != null && r.nextDouble() < 0.35) {
                    int mx = x + (alongX ? 0 : 1), mz = z + (alongX ? 1 : 0);
                    int[] ms = scan(cv, mx, mz);
                    if (ms != null && cv.type(mx, ms[0] + 1, mz) != null && cv.type(mx, ms[0] + 1, mz).isAir()) {
                        cv.set(mx, ms[0] + 1, mz, brown);
                    }
                }
            }
        }
        return made;
    }
}
