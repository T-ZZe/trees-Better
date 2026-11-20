package fr.arbres;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Palmiers de plage, pics de glace, pics de pierre moussus au bord de l'eau. */
final class ExtraGen {
    private ExtraGen() {}

    private static final int NONE = Integer.MIN_VALUE;

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

    /** Premier bloc solide en descendant (feuillage, herbes... ignorés). Eau => NONE. */
    static int surface(Canvas cv, int x, int z) {
        int y = cv.topY(x, z);
        while (y > cv.minY()) {
            Material m = cv.type(x, y, z);
            if (m == null) return NONE;
            if (m.isAir() || Blocks.leaf(m) || Blocks.log(m) || Blocks.replaceable(m)) {
                y--;
                continue;
            }
            if (Blocks.liquid(m)) return NONE;
            return y;
        }
        return NONE;
    }

    private static boolean nearWater(Canvas cv, int x, int y, int z, int maxD) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int d = 2; d <= maxD; d += 2) {
            for (int[] v : dirs) {
                int px = x + v[0] * d, pz = z + v[1] * d;
                for (int dy = -2; dy <= 0; dy++) {
                    if (cv.type(px, y + dy, pz) == Material.WATER) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ palmiers de plage

    static int palms(Canvas cv, int chunkX, int chunkZ, long seed, double chance) {
        Random r = new Random(TreeGen.mix(seed * 13L + chunkX * 341873128712L + chunkZ * 132897987541L + 9001L));
        if (r.nextDouble() >= chance) return 0;
        BlockData log = Material.JUNGLE_LOG.createBlockData();
        BlockData leaf = Material.JUNGLE_LEAVES.createBlockData();
        if (leaf instanceof Leaves l) {
            l.setPersistent(true);
            l.setDistance(1);
        }
        int wanted = 1 + r.nextInt(4);
        int mx = chunkX * 16 + 4 + r.nextInt(8);
        int mz = chunkZ * 16 + 4 + r.nextInt(8);
        List<int[]> placed = new ArrayList<>();
        int made = 0;
        for (int attempt = 0; attempt < 24 && made < wanted; attempt++) {
            int x = mx + r.nextInt(11) - 5, z = mz + r.nextInt(11) - 5;
            int y = surface(cv, x, z);
            if (y == NONE) continue;
            if (cv.type(x, y, z) != Material.SAND) continue;
            String biome = cv.biome(x, y, z);
            if (!biome.contains("beach") || biome.contains("snowy")) continue;
            boolean far = true;
            for (int[] p : placed) {
                if (Math.abs(p[0] - x) + Math.abs(p[1] - z) < 4) far = false;
            }
            if (!far || !nearWater(cv, x, y, z, 10)) continue;
            boolean free = true;
            for (int k = 1; k <= 12 && free; k++) {
                Material a = cv.type(x, y + k, z);
                if (a == null || !(a.isAir() || Blocks.replaceable(a))) free = false;
            }
            if (!free) continue;
            OasisGen.palm(cv, r, x, y, z, log, leaf, 6 + r.nextInt(6));
            placed.add(new int[] {x, z});
            made++;
        }
        return made;
    }

    // ------------------------------------------------------------------ pics de glace

    private static boolean iceGround(Material m) {
        String n = m.name();
        return n.equals("SNOW_BLOCK") || n.equals("PACKED_ICE") || n.equals("ICE") || n.equals("BLUE_ICE")
                || n.equals("POWDER_SNOW");
    }

    private static boolean iceish(Material m) {
        String n = m.name();
        return n.equals("PACKED_ICE") || n.equals("ICE") || n.equals("BLUE_ICE");
    }

    private static boolean anyIce(Material m) {
        return iceish(m);
    }

    /** Les pics du jeu sont en glace compactée. Les nôtres utilisent seulement de la glace et de la glace bleue : on ne les confond jamais. */
    private static boolean vanillaIce(Material m) {
        return m == Material.PACKED_ICE;
    }

    /**
     * Supprime les pics de glace du jeu (glace compactée au-dessus du sol du biome). Nos propres pics (glace / glace bleue)
     * ne sont jamais touchés, même s'ils débordent d'un chunk voisin. Retourne le nombre de blocs retirés.
     */
    static int clearVanillaSpikes(Canvas cv, int x0, int z0, int x1, int z1) {
        int[][] samples = {{8, 8}, {2, 2}, {13, 2}, {2, 13}, {13, 13}};
        boolean ice = false;
        for (int[] sp : samples) {
            if (cv.biome(x0 + sp[0], 72, z0 + sp[1]).contains("ice_spikes")) ice = true;
        }
        if (!ice) return 0;
        int pad = 12;
        int w = (x1 - x0 + 1) + 2 * pad;
        int[][] top = new int[w][w];
        boolean[][] groundCol = new boolean[w][w]; // colonne dont le sommet n'est pas de la glace = vrai sol
        int minTop = Integer.MAX_VALUE;
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < w; dz++) {
                int y = surface(cv, x0 - pad + dx, z0 - pad + dz);
                top[dx][dz] = y;
                if (y == NONE) continue;
                Material m = cv.type(x0 - pad + dx, y, z0 - pad + dz);
                groundCol[dx][dz] = m != null && !anyIce(m);
                if (y < minTop) minTop = y;
            }
        }
        BlockData air = Material.AIR.createBlockData();
        int removed = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int ix = x - x0 + pad, iz = z - z0 + pad;
                if (top[ix][iz] == NONE) continue;
                Material tm = cv.type(x, top[ix][iz], z);
                if (tm == null || !vanillaIce(tm)) continue;
                // niveau du sol : colonne voisine la plus proche qui est du vrai sol, sinon la colonne la plus basse
                int gl = NONE;
                for (int rr = 1; rr <= pad && gl == NONE; rr++) {
                    for (int dx = -rr; dx <= rr && gl == NONE; dx++) {
                        for (int dz = -rr; dz <= rr; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != rr) continue;
                            int jx = ix + dx, jz = iz + dz;
                            if (jx < 0 || jz < 0 || jx >= w || jz >= w) continue;
                            if (groundCol[jx][jz]) {
                                gl = top[jx][jz];
                                break;
                            }
                        }
                    }
                }
                if (gl == NONE) gl = minTop;
                if (gl == Integer.MAX_VALUE) continue;
                for (int y = top[ix][iz]; y > gl; y--) {
                    Material m = cv.type(x, y, z);
                    if (m != null && vanillaIce(m)) {
                        cv.set(x, y, z, air);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** Un groupe de pics par case de 18x18 blocs au plus : espacés, jamais collés les uns aux autres. */
    static int iceSpikes(Canvas cv, int chunkX, int chunkZ, long seed, double chance) {
        final int G = 18;
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        int made = 0;
        for (int gx = Math.floorDiv(x0 - G, G); gx <= Math.floorDiv(x0 + 15, G); gx++) {
            for (int gz = Math.floorDiv(z0 - G, G); gz <= Math.floorDiv(z0 + 15, G); gz++) {
                Random r = new Random(TreeGen.mix(seed * 19L + gx * 341873128712L + gz * 132897987541L + 5151L));
                int ax = gx * G + 3 + r.nextInt(G - 6);
                int az = gz * G + 3 + r.nextInt(G - 6);
                boolean take = r.nextDouble() < chance;
                if (!take || ax < x0 || ax > x0 + 15 || az < z0 || az > z0 + 15) continue;
                int y = surface(cv, ax, az);
                if (y == NONE || !cv.biome(ax, y, az).contains("ice_spikes")) continue;
                Material g = cv.type(ax, y, az);
                if (g == null || g != Material.SNOW_BLOCK) continue;
                iceSpike(cv, r, seed, ax, y, az, 16 + r.nextInt(19), 2.2 + r.nextDouble() * 1.4, 0.20);
                made++;
                int sat = 1 + r.nextInt(3);
                for (int i = 0; i < sat; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double d = 4 + r.nextDouble() * 3;
                    int sx = ax + (int) Math.round(Math.cos(a) * d), sz = az + (int) Math.round(Math.sin(a) * d);
                    int sy = surface(cv, sx, sz);
                    if (sy == NONE) continue;
                    Material sg = cv.type(sx, sy, sz);
                    if (sg == null || sg != Material.SNOW_BLOCK) continue;
                    iceSpike(cv, r, seed, sx, sy, sz, 6 + r.nextInt(9), 1.2 + r.nextDouble() * 0.9, 0.25);
                    made++;
                }
            }
        }
        return made;
    }

    private static void iceSpike(Canvas cv, Random r, long seed, int x, int y, int z, int h, double baseR, double leanMax) {
        double ang = r.nextDouble() * Math.PI * 2;
        double lean = r.nextDouble() * leanMax;
        double lx = Math.cos(ang) * lean, lz = Math.sin(ang) * lean;
        BlockData packed = Material.PACKED_ICE.createBlockData(); // seulement sous le sol
        BlockData ice = Material.ICE.createBlockData();
        BlockData blue = Material.BLUE_ICE.createBlockData();
        long s = seed ^ (x * 73856093L) ^ (z * 19349663L);
        for (int k = -2; k <= h; k++) {
            double t = Math.max(0, k) / (double) h;
            double rad = baseR * Math.pow(1.0 - t, 1.1) + 0.45;
            double ccx = x + lx * Math.max(0, k), ccz = z + lz * Math.max(0, k);
            int ir = (int) Math.ceil(rad) + 1;
            int cxi = (int) Math.round(ccx), czi = (int) Math.round(ccz);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int px = cxi + dx, pz = czi + dz;
                    double ddx = px - ccx, ddz = pz - ccz;
                    double d2 = (ddx * ddx + ddz * ddz) / (rad * rad);
                    double n = hash(s, px, y + k, pz);
                    if (d2 + (n - 0.5) * 0.4 > 1.0) continue;
                    Material cur = cv.type(px, y + k, pz);
                    if (cur == null) continue;
                    boolean ok = cur.isAir() || Blocks.replaceable(cur) || (k <= 0 && iceGround(cur));
                    if (!ok) continue;
                    double n2 = hash(s + 1, px, y + k, pz);
                    BlockData d;
                    if (k <= 0) d = packed;
                    else if (k == h) d = ice;
                    else if (d2 < 0.35) d = n2 < 0.6 ? blue : ice;
                    else d = n2 < 0.3 ? blue : ice;
                    cv.set(px, y + k, pz, d);
                }
            }
        }
    }

    // ------------------------------------------------------------------ pics de pierre moussus

    private static boolean rockGround(Material m) {
        String n = m.name();
        return n.equals("STONE") || n.equals("ANDESITE") || n.equals("DIORITE") || n.equals("GRANITE")
                || n.equals("TUFF") || n.equals("GRAVEL") || n.equals("COBBLESTONE") || n.equals("DIRT")
                || n.equals("GRASS_BLOCK") || n.equals("COARSE_DIRT") || n.equals("SAND");
    }

    static int stoneSpikes(Canvas cv, int chunkX, int chunkZ, long seed, double chance, List<String> biomes) {
        Random r = new Random(TreeGen.mix(seed * 23L + chunkX * 341873128712L + chunkZ * 132897987541L + 3131L));
        if (r.nextDouble() >= chance) return 0;
        int wanted = 1 + r.nextInt(3);
        int made = 0;
        for (int attempt = 0; attempt < 12 && made < wanted; attempt++) {
            int x = chunkX * 16 + 3 + r.nextInt(10), z = chunkZ * 16 + 3 + r.nextInt(10);
            int y = surface(cv, x, z);
            if (y == NONE) continue;
            Material g = cv.type(x, y, z);
            if (g == null || !rockGround(g)) continue;
            String biome = cv.biome(x, y, z);
            boolean okBiome = false;
            for (String b : biomes) {
                if (biome.contains(b)) okBiome = true;
            }
            if (!okBiome || !nearWater(cv, x, y, z, 9)) continue;
            stoneSpike(cv, r, seed, x, y, z, 5 + r.nextInt(12), 1.3 + r.nextDouble() * 1.6);
            made++;
        }
        return made;
    }

    private static void stoneSpike(Canvas cv, Random r, long seed, int x, int y, int z, int h, double baseR) {
        double ang = r.nextDouble() * Math.PI * 2;
        double lean = r.nextDouble() * 0.22;
        double lx = Math.cos(ang) * lean, lz = Math.sin(ang) * lean;
        long s = seed ^ (x * 73856093L) ^ (z * 19349663L);
        Map<Long, Integer> tops = new HashMap<>();
        Map<Long, int[]> where = new HashMap<>();
        BlockData stone = Material.STONE.createBlockData();
        BlockData andesite = Material.ANDESITE.createBlockData();
        BlockData cobble = Material.COBBLESTONE.createBlockData();
        BlockData mossyCobble = Material.MOSSY_COBBLESTONE.createBlockData();
        BlockData moss = Material.MOSS_BLOCK.createBlockData();
        for (int k = -1; k <= h; k++) {
            double t = Math.max(0, k) / (double) h;
            double rad = baseR * (1.0 - Math.pow(t, 1.15)) + 0.35;
            double ccx = x + lx * Math.max(0, k), ccz = z + lz * Math.max(0, k);
            int ir = (int) Math.ceil(rad) + 1;
            int cxi = (int) Math.round(ccx), czi = (int) Math.round(ccz);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int px = cxi + dx, pz = czi + dz;
                    double ddx = px - ccx, ddz = pz - ccz;
                    double d2 = (ddx * ddx + ddz * ddz) / (rad * rad);
                    double n = hash(s, px, y + k, pz);
                    if (d2 + (n - 0.5) * 0.5 > 1.0) continue;
                    Material cur = cv.type(px, y + k, pz);
                    if (cur == null) continue;
                    boolean ok = cur.isAir() || Blocks.replaceable(cur) || (k <= 0 && rockGround(cur));
                    if (!ok) continue;
                    double n2 = hash(s + 1, px, y + k, pz);
                    BlockData d;
                    if (k >= h - 1 || (k > h * 0.4 && n2 < 0.12)) d = moss;
                    else if (n2 < 0.50) d = stone;
                    else if (n2 < 0.70) d = andesite;
                    else if (n2 < 0.85) d = cobble;
                    else d = mossyCobble;
                    cv.set(px, y + k, pz, d);
                    long key = ((long) px << 32) ^ (pz & 0xffffffffL);
                    Integer old = tops.get(key);
                    if (old == null || old < y + k) {
                        tops.put(key, y + k);
                        where.put(key, new int[] {px, pz});
                    }
                }
            }
        }
        // mousse au sommet de chaque colonne : bloc de mousse + tapis, et parfois une azalée
        BlockData carpet = mat("MOSS_CARPET");
        Material az = Material.getMaterial(r.nextBoolean() ? "AZALEA" : "FLOWERING_AZALEA");
        int best = Integer.MIN_VALUE;
        int[] bestPos = null;
        for (Map.Entry<Long, Integer> e : tops.entrySet()) {
            int[] p = where.get(e.getKey());
            int ty = e.getValue();
            if (ty > best) {
                best = ty;
                bestPos = p;
            }
            Material cur = cv.type(p[0], ty, p[1]);
            if (cur == null) continue;
            cv.set(p[0], ty, p[1], moss);
            Material above = cv.type(p[0], ty + 1, p[1]);
            if (above != null && above.isAir() && carpet != null && hash(s + 2, p[0], ty, p[1]) < 0.75) {
                cv.set(p[0], ty + 1, p[1], carpet);
            }
        }
        if (bestPos != null && az != null) {
            Material above = cv.type(bestPos[0], best + 1, bestPos[1]);
            if (above != null && (above.isAir() || Blocks.replaceable(above)) && r.nextDouble() < 0.6) {
                cv.set(bestPos[0], best + 1, bestPos[1], az.createBlockData());
            }
        }
    }

    private static BlockData mat(String name) {
        Material m = Material.getMaterial(name);
        return m == null ? null : m.createBlockData();
    }
}
