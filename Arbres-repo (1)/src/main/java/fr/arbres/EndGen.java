package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Arbres sur les îles extérieures de l'End et tours d'obsidienne embellies sur l'île centrale. */
final class EndGen {
    private EndGen() {}

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

    private static Material mat(String name, Material fallback) {
        Material m = Material.getMaterial(name);
        return m == null ? fallback : m;
    }

    // ================================================================== zones mortes + arbres d'os

    /** Premier bloc solide en descendant depuis le haut des îles (herbes et plantes de chorus ignorées). */
    private static int groundY(Canvas cv, int x, int z) {
        for (int y = Math.min(cv.maxY() - 1, 130); y > 20; y--) {
            Material m = cv.type(x, y, z);
            if (m == null) return NONE;
            if (m.isAir() || Blocks.replaceable(m)) continue;
            return y;
        }
        return NONE;
    }

    private static boolean deadGroundType(Material m) {
        return m == Material.END_STONE;
    }

    /** Arbres d'os sur les îles extérieures (à plus de 700 blocs du centre). Aucun changement de sol ni de biome. */
    static int boneForest(Canvas cv, int chunkX, int chunkZ, long seed, double chance, double scale) {
        long wx = chunkX * 16L + 8, wz = chunkZ * 16L + 8;
        if (wx * wx + wz * wz < 700L * 700L) return 0;
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        Random r = new Random(TreeGen.mix(seed * 29L + chunkX * 341873128712L + chunkZ * 132897987541L + 777L));
        if (r.nextDouble() >= chance) return 0;
        int wanted = 1 + r.nextInt(3);
        int made = 0;
        List<int[]> placed = new ArrayList<>();
        for (int attempt = 0; attempt < 16 && made < wanted; attempt++) {
            int x = x0 + 3 + r.nextInt(10), z = z0 + 3 + r.nextInt(10);
            int y = groundY(cv, x, z);
            if (y == NONE) continue;
            Material g = cv.type(x, y, z);
            if (g == null || !deadGroundType(g)) continue;
            boolean flat = true;
            int[][] around = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
            for (int[] a : around) {
                int gy = groundY(cv, x + a[0], z + a[1]);
                if (gy == NONE || Math.abs(gy - y) > 2) flat = false;
            }
            boolean far = true;
            for (int[] p : placed) {
                if (Math.abs(p[0] - x) + Math.abs(p[1] - z) < 8) far = false;
            }
            if (!flat || !far) continue;
            Random tr = new Random(TreeGen.mix(seed ^ (x * 341873128712L) ^ (z * 132897987541L) ^ y));
            if (boneTree(cv, tr, x, y, z, scale)) {
                placed.add(new int[] {x, z});
                made++;
            }
        }
        return made;
    }

    private static void line(Map<TreeGen.P, Axis> out, int x1, int y1, int z1, int x2, int y2, int z2) {
        int x = x1, y = y1, z = z1;
        out.putIfAbsent(new TreeGen.P(x, y, z), Axis.Y);
        int guard = 0;
        while ((x != x2 || y != y2 || z != z2) && guard++ < 200) {
            int ddx = Integer.compare(x2, x), ddy = Integer.compare(y2, y), ddz = Integer.compare(z2, z);
            int ax = Math.abs(x2 - x), ay = Math.abs(y2 - y), az = Math.abs(z2 - z);
            Axis a;
            if (ax >= ay && ax >= az) {
                x += ddx;
                a = Axis.X;
            } else if (az >= ay) {
                z += ddz;
                a = Axis.Z;
            } else {
                y += ddy;
                a = Axis.Y;
            }
            out.putIfAbsent(new TreeGen.P(x, y, z), a);
        }
    }

    /** Arbre squelette : tronc d'os tordu, racines, branches fourchues, griffes, toiles et crânes. */
    private static boolean boneTree(Canvas cv, Random r, int x, int y, int z, double scale) {
        double sc = Math.max(0.6, Math.min(2.0, scale));
        int H = (int) Math.round((9 + r.nextInt(9)) * sc);
        for (int k = 1; k <= H + 6; k++) {
            Material a = cv.type(x, y + k, z);
            if (a == null || !(a.isAir() || Blocks.replaceable(a))) return false;
        }
        int th = 1;
        if (r.nextInt(3) == 0) {
            boolean ok = true;
            int[][] ex = {{1, 0}, {0, 1}, {1, 1}};
            for (int[] e : ex) {
                Material g = cv.type(x + e[0], y, z + e[1]);
                Material up = cv.type(x + e[0], y + 1, z + e[1]);
                if (g == null || !deadGroundType(g) || up == null || !(up.isAir() || Blocks.replaceable(up))) ok = false;
            }
            if (ok) th = 2;
        }
        Map<TreeGen.P, Axis> bones = new LinkedHashMap<>();
        List<TreeGen.P> tips = new ArrayList<>();
        double dir = r.nextDouble() * Math.PI * 2;
        double lean = r.nextDouble() * 2.2;
        int[] ox = new int[H + 1];
        int[] oz = new int[H + 1];
        int px = 0, pz = 0;
        for (int k = 0; k <= H; k++) {
            double t = k / (double) H;
            int nx = (int) Math.round(Math.cos(dir) * lean * t * t);
            int nz = (int) Math.round(Math.sin(dir) * lean * t * t);
            if (k > 0 && (nx != px || nz != pz)) {
                for (int i = 0; i < th; i++) {
                    for (int j = 0; j < th; j++) bones.putIfAbsent(new TreeGen.P(x + nx + i, y + k, z + nz + j), Axis.Y);
                }
            }
            px = nx;
            pz = nz;
            ox[k] = nx;
            oz[k] = nz;
            for (int i = 0; i < th; i++) {
                for (int j = 0; j < th; j++) bones.putIfAbsent(new TreeGen.P(x + nx + i, y + 1 + k, z + nz + j), Axis.Y);
            }
        }
        // racines qui rampent sur le sol
        int[][] dirs4 = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] d : dirs4) {
            int len = 2 + r.nextInt(3);
            for (int k = 1; k <= len; k++) {
                int rx = d[0] > 0 ? th - 1 + k : (d[0] < 0 ? -k : 0);
                int rz = d[1] > 0 ? th - 1 + k : (d[1] < 0 ? -k : 0);
                bones.putIfAbsent(new TreeGen.P(x + rx, y + 1, z + rz), d[0] != 0 ? Axis.X : Axis.Z);
            }
        }
        // branches fourchues
        int nb = 4 + r.nextInt(4);
        double a0 = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < nb; i++) {
            double frac = 0.35 + 0.6 * i / Math.max(1, nb - 1);
            int yy = (int) Math.round(H * frac);
            double ang = a0 + i * 2.4 + (r.nextDouble() - 0.5) * 0.6;
            int len = (int) Math.round((3 + r.nextInt(5)) * (1.2 - 0.5 * frac) * Math.sqrt(sc));
            int rise = r.nextInt(4) - 1;
            int sx = x + ox[yy], sz = z + oz[yy], sy = y + 1 + yy;
            int ex = sx + (int) Math.round(Math.cos(ang) * len), ez = sz + (int) Math.round(Math.sin(ang) * len);
            int ey = sy + rise;
            line(bones, sx, sy, sz, ex, ey, ez);
            int forks = 2 + r.nextInt(2);
            for (int f = 0; f < forks; f++) {
                double a2 = ang + (f - (forks - 1) / 2.0) * 0.9;
                int tl = 2 + r.nextInt(3);
                int tx = ex + (int) Math.round(Math.cos(a2) * tl), tz = ez + (int) Math.round(Math.sin(a2) * tl);
                int ty = ey + r.nextInt(3) - 1;
                line(bones, ex, ey, ez, tx, ty, tz);
                tips.add(new TreeGen.P(tx, ty, tz));
                if (r.nextDouble() < 0.4) { // griffe qui retombe
                    bones.putIfAbsent(new TreeGen.P(tx, ty - 1, tz), Axis.Y);
                    bones.putIfAbsent(new TreeGen.P(tx, ty - 2, tz), Axis.Y);
                }
            }
        }
        // pointe au sommet
        for (int k = 1; k <= 3; k++) bones.putIfAbsent(new TreeGen.P(x + ox[H], y + 1 + H + k, z + oz[H]), Axis.Y);

        BlockData[] data = new BlockData[3];
        for (Axis ax : new Axis[] {Axis.X, Axis.Y, Axis.Z}) {
            BlockData d = Material.BONE_BLOCK.createBlockData();
            if (d instanceof Orientable o) o.setAxis(ax);
            data[ax.ordinal()] = d;
        }
        for (Map.Entry<TreeGen.P, Axis> e : bones.entrySet()) {
            TreeGen.P p = e.getKey();
            Material t = cv.type(p.x(), p.y(), p.z());
            if (t == null || !(t.isAir() || Blocks.replaceable(t))) continue;
            cv.set(p.x(), p.y(), p.z(), data[e.getValue().ordinal()]);
        }
        // toiles d'araignée dans les fourches, crânes au bout de certaines branches
        BlockData web = Material.COBWEB.createBlockData();
        BlockData skull = Material.SKELETON_SKULL.createBlockData();
        for (TreeGen.P p : tips) {
            double u = r.nextDouble();
            Material above = cv.type(p.x(), p.y() + 1, p.z());
            if (u < 0.18 && above != null && above.isAir()) {
                cv.set(p.x(), p.y() + 1, p.z(), skull);
            } else if (u < 0.5) {
                int wx = p.x() + (r.nextBoolean() ? 1 : -1), wz = p.z() + (r.nextBoolean() ? 1 : -1);
                Material q = cv.type(wx, p.y(), wz);
                if (q != null && q.isAir()) cv.set(wx, p.y(), wz, web);
            }
        }
        return true;
    }

    // ================================================================== tours d'obsidienne

    static int towers(Canvas cv, int chunkX, int chunkZ, long seed) {
        if (Math.abs(chunkX) > 4 || Math.abs(chunkZ) > 4) return 0;
        int made = 0;
        for (int i = 0; i < 10; i++) { // mêmes positions que les pics vanilla
            double a = 2.0 * (-Math.PI + (Math.PI / 10.0) * i);
            int cx = (int) Math.floor(42.0 * Math.cos(a));
            int cz = (int) Math.floor(42.0 * Math.sin(a));
            if ((cx >> 4) != chunkX || (cz >> 4) != chunkZ) continue;
            if (tower(cv, cx, cz, seed)) made++;
        }
        return made;
    }

    private static boolean tower(Canvas cv, int cx, int cz, long seed) {
        int h = NONE;
        for (int y = Math.min(cv.maxY() - 1, 120); y >= 50; y--) {
            Material m = cv.type(cx, y, cz);
            if (m == null) return false;
            if (m == Material.BEDROCK) { // la base du cristal
                Material b = cv.type(cx, y - 1, cz);
                if (b == Material.OBSIDIAN || b == Material.CRYING_OBSIDIAN) h = y;
                break;
            }
        }
        if (h == NONE) return false;
        if (cv.type(cx, h - 1, cz) == Material.CRYING_OBSIDIAN) return false; // déjà embellie
        int rad = 0;
        while (rad < 8 && cv.type(cx + rad + 1, h - 3, cz) == Material.OBSIDIAN) rad++;
        if (rad < 2) return false;

        // hauteur du sol : fond de l'île autour de la tour
        int gy = NONE;
        int[][] probes = {{rad + 3, 0}, {-(rad + 3), 0}, {0, rad + 3}, {0, -(rad + 3)}};
        for (int[] p : probes) {
            for (int y = h; y > 30; y--) {
                Material m = cv.type(cx + p[0], y, cz + p[1]);
                if (m == null) break;
                if (m == Material.END_STONE) {
                    gy = Math.max(gy, y);
                    break;
                }
            }
        }
        if (gy == NONE || h - gy < 10) return false;

        Material obs = Material.OBSIDIAN;
        Material cry = Material.CRYING_OBSIDIAN;
        Material bs = mat("BLACKSTONE", obs);
        Material pbb = mat("POLISHED_BLACKSTONE_BRICKS", obs);
        Material pur = mat("PURPUR_PILLAR", obs);
        BlockData dObs = obs.createBlockData();
        BlockData dCry = cry.createBlockData();
        BlockData dBs = bs.createBlockData();
        BlockData dPbb = pbb.createBlockData();
        BlockData dPur = pur.createBlockData();
        BlockData rod = mat("END_ROD", Material.AIR).createBlockData();

        // 1) peau du fût : bandes, colonnes de purpur aux 4 points cardinaux, un peu d'obsidienne pleureuse
        for (int y = gy + 1; y <= h - 1; y++) {
            int k = y - gy;
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > rad * rad || d2 < (rad - 1) * (rad - 1)) continue;
                    if (cv.type(cx + dx, y, cz + dz) != Material.OBSIDIAN) continue;
                    double n = hash(seed, cx + dx, y, cz + dz);
                    BlockData d;
                    if ((dx == 0 || dz == 0)) d = dPur;
                    else if (k % 9 == 0 || (k % 9 == 1 && n < 0.5)) d = dPbb;
                    else if (n < 0.10) d = dCry;
                    else if (n < 0.24) d = dBs;
                    else d = dObs;
                    cv.set(cx + dx, y, cz + dz, d);
                }
            }
        }

        // 2) pied évasé
        for (int k = 1; k <= 9; k++) {
            double ext = 3.2 - k * 0.36;
            if (ext <= 0.3) break;
            double R = rad + ext;
            int ir = (int) Math.ceil(R);
            int y = gy + k;
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > R * R || d2 <= rad * rad) continue;
                    Material t = cv.type(cx + dx, y, cz + dz);
                    if (t == null || !(t.isAir() || Blocks.replaceable(t))) continue;
                    double n = hash(seed + 1, cx + dx, y, cz + dz);
                    cv.set(cx + dx, y, cz + dz, n < 0.35 ? dObs : n < 0.6 ? dBs : dPbb);
                    if (k == 1) { // combler le vide sous le pied
                        for (int yy = y - 1; yy > y - 7; yy--) {
                            Material u = cv.type(cx + dx, yy, cz + dz);
                            if (u == null || !u.isAir()) break;
                            cv.set(cx + dx, yy, cz + dz, dBs);
                        }
                    }
                }
            }
        }

        // 3) balcons avec lumières
        int[] ledges = {gy + (h - gy) / 2, h - 9};
        for (int ly : ledges) {
            if (ly <= gy + 6 || ly >= h - 2) continue;
            ring(cv, cx, cz, ly, rad + 0.5, rad + 2.4, dPbb);
            cardinalRods(cv, cx, cz, ly + 1, rad + 2, rod);
        }

        // 4) couronne sous le cristal (rien n'est touché à partir de la bedrock)
        ring(cv, cx, cz, h - 1, rad + 0.5, rad + 1.6, dPbb);
        cardinalRods(cv, cx, cz, h, rad + 1, rod);

        // 5) marqueur (caché dans le fût) pour ne jamais refaire la tour
        cv.set(cx, h - 1, cz, dCry);
        return true;
    }

    /** Anneau de blocs dans l'air entre deux rayons. */
    private static void ring(Canvas cv, int cx, int cz, int y, double r0, double r1, BlockData d) {
        int ir = (int) Math.ceil(r1);
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 <= r0 * r0 || d2 > r1 * r1) continue;
                Material t = cv.type(cx + dx, y, cz + dz);
                if (t != null && t.isAir()) cv.set(cx + dx, y, cz + dz, d);
            }
        }
    }

    private static void cardinalRods(Canvas cv, int cx, int cz, int y, int dist, BlockData rod) {
        if (rod.getMaterial() == Material.AIR) return;
        int[][] c = {{dist, 0}, {-dist, 0}, {0, dist}, {0, -dist}};
        for (int[] p : c) {
            Material t = cv.type(cx + p[0], y, cz + p[1]);
            Material below = cv.type(cx + p[0], y - 1, cz + p[1]);
            if (t != null && t.isAir() && below != null && !below.isAir()) cv.set(cx + p[0], y, cz + p[1], rod);
        }
    }
}
