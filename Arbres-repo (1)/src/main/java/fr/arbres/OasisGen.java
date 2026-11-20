package fr.arbres;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Petites oasis dans les déserts et les mesas : étang, herbe, palmiers, et parfois un campement avec un coffre. */
final class OasisGen {
    private OasisGen() {}

    interface ChestSink {
        void add(String world, int x, int y, int z);
    }

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

    /** Génère (éventuellement) une oasis dans ce chunk. Retourne le nombre d'oasis posées. */
    static int oasis(Canvas cv, String world, int chunkX, int chunkZ, long worldSeed, double chance,
                     double campChance, ChestSink sink) {
        Random r = new Random(TreeGen.mix(worldSeed * 17L + chunkX * 341873128712L + chunkZ * 132897987541L + 424242L));
        if (r.nextDouble() >= chance) return 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            int cx = chunkX * 16 + 5 + r.nextInt(7);
            int cz = chunkZ * 16 + 5 + r.nextInt(7);
            if (tryOne(cv, r, world, cx, cz, campChance, sink, worldSeed)) return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ terrain

    private static boolean plant(Material m) {
        return Blocks.replaceable(m) && !Blocks.leaf(m);
    }

    private static boolean passable(Material m) {
        return m.isAir() || Blocks.replaceable(m) || m == Material.CACTUS;
    }

    private static boolean natural(Material m) {
        String n = m.name();
        return n.equals("SAND") || n.equals("RED_SAND") || n.equals("SANDSTONE") || n.equals("RED_SANDSTONE")
                || n.equals("TERRACOTTA") || n.endsWith("_TERRACOTTA");
    }

    private static int surface(Canvas cv, int x, int z) {
        int y = cv.topY(x, z);
        while (y > cv.minY()) {
            Material m = cv.type(x, y, z);
            if (m == null) return NONE;
            if (passable(m)) {
                y--;
                continue;
            }
            return y;
        }
        return NONE;
    }

    /** Surface naturelle et plate, sans rien de construit au-dessus. */
    private static boolean flatNatural(Canvas cv, int x, int z, int y0, int[] out) {
        int sy = surface(cv, x, z);
        if (sy == NONE || Math.abs(sy - y0) > 1) return false;
        Material m = cv.type(x, sy, z);
        if (m == null || !natural(m)) return false;
        for (int k = 1; k <= 3; k++) {
            Material a = cv.type(x, sy + k, z);
            if (a == null || !(plant(a) || a == Material.CACTUS)) return false;
        }
        out[0] = sy;
        return true;
    }

    // ------------------------------------------------------------------ oasis

    private static boolean tryOne(Canvas cv, Random r, String world, int cx, int cz, double campChance,
                                  ChestSink sink, long worldSeed) {
        int y0 = surface(cv, cx, cz);
        if (y0 == NONE) return false;
        Material g0 = cv.type(cx, y0, cz);
        if (g0 == null || !natural(g0)) return false;
        String biome = cv.biome(cx, y0, cz);
        if (!(biome.contains("desert") || biome.contains("badlands"))) return false;

        double rx = 3.0 + r.nextDouble() * 2.0;
        double rz = 2.8 + r.nextDouble() * 2.0;
        double ringR = Math.max(rx, rz) + 2.8;
        int R = (int) Math.ceil(ringR) + 1;
        int W = 2 * R + 1;
        long seed = TreeGen.mix(worldSeed ^ (cx * 73856093L) ^ (cz * 19349663L));

        // 1) le terrain doit être naturel et plat sur toute la zone
        int[][] surf = new int[W][W];
        boolean[][] inside = new boolean[W][W];
        int[] tmp = new int[1];
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz > (ringR + 1) * (ringR + 1)) continue;
                if (!flatNatural(cv, cx + dx, cz + dz, y0, tmp)) return false;
                surf[dx + R][dz + R] = tmp[0];
                inside[dx + R][dz + R] = true;
            }
        }

        // 2) forme de l'étang
        boolean[][] pond = new boolean[W][W];
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (!inside[dx + R][dz + R]) continue;
                double e = (dx / rx) * (dx / rx) + (dz / rz) * (dz / rz) + (hash(seed, dx, 7, dz) - 0.5) * 0.55;
                pond[dx + R][dz + R] = e <= 1.0;
            }
        }
        // l'étang ne doit pas percer une grotte : le sol dessous et autour doit être plein
        for (int dx = -R + 1; dx <= R - 1; dx++) {
            for (int dz = -R + 1; dz <= R - 1; dz++) {
                if (!pond[dx + R][dz + R]) continue;
                for (int ax = -1; ax <= 1; ax++) {
                    for (int az = -1; az <= 1; az++) {
                        for (int k = 1; k <= 3; k++) {
                            Material m = cv.type(cx + dx + ax, y0 - k, cz + dz + az);
                            if (m == null || m.isAir() || m == Material.WATER || m.name().equals("LAVA")) return false;
                        }
                    }
                }
            }
        }

        BlockData water = Material.WATER.createBlockData();
        BlockData air = Material.AIR.createBlockData();
        BlockData grass = Material.GRASS_BLOCK.createBlockData();
        BlockData dirt = Material.DIRT.createBlockData();
        Material sandMat = g0.name().startsWith("RED") || g0.name().contains("TERRACOTTA") ? Material.RED_SAND : Material.SAND;
        BlockData sand = sandMat.createBlockData();

        // 3) terrain : étang, remblai autour, anneau d'herbe
        int[][] gy = new int[W][W];
        boolean[][] grassZone = new boolean[W][W];
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (!inside[dx + R][dz + R]) continue;
                int x = cx + dx, z = cz + dz;
                int sy = surf[dx + R][dz + R];
                // on enlève cactus / buissons secs sur toute la zone
                for (int k = 1; k <= 3; k++) {
                    Material a = cv.type(x, sy + k, z);
                    if (a != null && !a.isAir()) cv.set(x, sy + k, z, air);
                }
                if (pond[dx + R][dz + R]) {
                    for (int y = y0 + 1; y <= sy; y++) cv.set(x, y, z, air);
                    cv.set(x, y0, z, water);
                    cv.set(x, y0 - 1, z, water);
                    cv.set(x, y0 - 2, z, water);
                    cv.set(x, y0 - 3, z, sand);
                    if (hash(seed, dx, 23, dz) < 0.10) cv.set(x, y0 + 1, z, Material.LILY_PAD.createBlockData());
                    gy[dx + R][dz + R] = y0;
                } else {
                    int g = sy;
                    if (sy < y0) { // remblai : le bord doit être au niveau de l'eau, sinon elle coulerait
                        for (int y = sy + 1; y <= y0; y++) cv.set(x, y, z, sand);
                        g = y0;
                    }
                    gy[dx + R][dz + R] = g;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= ringR - (hash(seed, dx, 11, dz) - 0.5) * 1.4) {
                        grassZone[dx + R][dz + R] = true;
                        cv.set(x, g, z, grass);
                        cv.set(x, g - 1, z, dirt);
                    }
                }
            }
        }

        // 4) végétation de l'anneau d'herbe : herbes, fougères, canne à sucre au bord de l'eau
        BlockData shortGrass = Material.SHORT_GRASS.createBlockData();
        BlockData fern = Material.FERN.createBlockData();
        BlockData cane = Material.SUGAR_CANE.createBlockData();
        BlockData[] flowers = {Material.ALLIUM.createBlockData(), Material.AZURE_BLUET.createBlockData(),
                Material.CORNFLOWER.createBlockData(), Material.BLUE_ORCHID.createBlockData(), Material.OXEYE_DAISY.createBlockData()};
        List<int[]> palmSpots = new ArrayList<>();
        for (int dx = -R + 1; dx <= R - 1; dx++) {
            for (int dz = -R + 1; dz <= R - 1; dz++) {
                if (!grassZone[dx + R][dz + R]) continue;
                int x = cx + dx, z = cz + dz, g = gy[dx + R][dz + R];
                boolean nearWater = pond[dx + R + 1][dz + R] || pond[dx + R - 1][dz + R]
                        || pond[dx + R][dz + R + 1] || pond[dx + R][dz + R - 1];
                double u = hash(seed, dx, 13, dz);
                if (nearWater && g == y0 && u < 0.35) {
                    int h = 1 + (int) (hash(seed, dx, 17, dz) * 3);
                    for (int k = 1; k <= h; k++) cv.set(x, g + k, z, cane);
                } else if (u < 0.55) {
                    cv.set(x, g + 1, z, u < 0.50 ? shortGrass : fern);
                } else if (u < 0.60) {
                    cv.set(x, g + 1, z, flowers[(int) (hash(seed, dx, 19, dz) * flowers.length)]);
                }
                boolean awayFromWater = true;
                for (int ax = -1; ax <= 1 && awayFromWater; ax++) {
                    for (int az = -1; az <= 1; az++) {
                        if (pond[dx + R + ax][dz + R + az]) {
                            awayFromWater = false;
                            break;
                        }
                    }
                }
                if (awayFromWater && g == y0) palmSpots.add(new int[] {x, g, z});
            }
        }

        // 5) palmiers (3 à 5), espacés
        Collections.shuffle(palmSpots, r);
        BlockData log = Material.JUNGLE_LOG.createBlockData();
        BlockData leaf = Material.JUNGLE_LEAVES.createBlockData();
        if (leaf instanceof Leaves l) {
            l.setPersistent(true);
            l.setDistance(1);
        }
        int wantPalms = 3 + r.nextInt(3);
        List<int[]> placed = new ArrayList<>();
        for (int[] s : palmSpots) {
            if (placed.size() >= wantPalms) break;
            boolean ok = true;
            for (int[] p : placed) {
                if (Math.abs(p[0] - s[0]) + Math.abs(p[2] - s[2]) < 5) ok = false;
            }
            if (!ok) continue;
            placed.add(s);
            cv.set(s[0], s[1] + 1, s[2], air);
            palm(cv, r, s[0], s[1], s[2], log, leaf, 6 + r.nextInt(5));
        }

        // 5b) ruines de pierre de sable autour de l'oasis
        ruins(cv, r, cx, cz, ringR, y0);

        // 6) campement
        if (r.nextDouble() < campChance) camp(cv, r, world, cx, cz, ringR, y0, sink);

        // 7) quelques cactus dans le sable autour
        BlockData cactus = Material.CACTUS.createBlockData();
        for (int i = 0; i < 4; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = ringR + 1.0 + r.nextDouble() * 3.0;
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            int sy = surface(cv, x, z);
            if (sy == NONE || Math.abs(sy - y0) > 1) continue;
            Material m = cv.type(x, sy, z);
            if (m == null || !(m.name().equals("SAND") || m.name().equals("RED_SAND"))) continue;
            int h = 1 + r.nextInt(3);
            boolean free = true;
            for (int k = 1; k <= h && free; k++) {
                for (int[] dd : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Material q = cv.type(x + dd[0], sy + k, z + dd[1]);
                    if (q == null || !(q.isAir() || plant(q))) free = false;
                }
            }
            if (!free) continue;
            for (int k = 1; k <= h; k++) cv.set(x, sy + k, z, cactus);
        }

        return true;
    }

    private static void ruins(Canvas cv, Random r, int cx, int cz, double ringR, int y0) {
        BlockData cut = Material.CUT_SANDSTONE.createBlockData();
        BlockData chis = Material.CHISELED_SANDSTONE.createBlockData();
        BlockData smooth = Material.SMOOTH_SANDSTONE.createBlockData();
        int n = 1 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = ringR + 1.5 + r.nextDouble() * 2.5;
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            int sy = surface(cv, x, z);
            if (sy == NONE || Math.abs(sy - y0) > 1) continue;
            Material g = cv.type(x, sy, z);
            if (g == null || !natural(g)) continue;
            int h = 2 + r.nextInt(4);
            boolean free = true;
            for (int k = 1; k <= h + 1 && free; k++) {
                Material q = cv.type(x, sy + k, z);
                if (q == null || !(q.isAir() || plant(q))) free = false;
            }
            if (!free) continue;
            for (int k = 1; k <= h; k++) cv.set(x, sy + k, z, k == h && r.nextBoolean() ? chis : cut);
            // éboulis au pied
            for (int[] dd : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (r.nextDouble() < 0.45) {
                    int bx = x + dd[0], bz = z + dd[1];
                    int by = surface(cv, bx, bz);
                    if (by == NONE || Math.abs(by - sy) > 1) continue;
                    Material q = cv.type(bx, by + 1, bz);
                    if (q != null && (q.isAir() || plant(q))) cv.set(bx, by + 1, bz, smooth);
                }
            }
        }
    }

    // ------------------------------------------------------------------ palmier

    private static void setLog(Canvas cv, BlockData base, int x, int y, int z) {
        Material t = cv.type(x, y, z);
        if (t == null || !(t.isAir() || Blocks.replaceable(t) || t == Material.SUGAR_CANE)) return;
        BlockData d = base.clone();
        if (d instanceof Orientable o) o.setAxis(Axis.Y);
        cv.set(x, y, z, d);
    }

    private static void setLeaf(Canvas cv, BlockData leaf, int x, int y, int z) {
        Material t = cv.type(x, y, z);
        if (t == null || !(t.isAir() || plant(t))) return;
        cv.set(x, y, z, leaf);
    }

    static void palm(Canvas cv, Random r, int px, int gy, int pz, BlockData log, BlockData leaf) {
        palm(cv, r, px, gy, pz, log, leaf, 5 + r.nextInt(4));
    }

    static void palm(Canvas cv, Random r, int px, int gy, int pz, BlockData log, BlockData leaf, int h) {
        int[][] card = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int[] d = card[r.nextInt(4)];
        int prev = 0, tx = px, tz = pz;
        for (int y = 1; y <= h; y++) {
            int off = Math.min(3, y / 2);
            tx = px + d[0] * off;
            tz = pz + d[1] * off;
            if (off != prev) setLog(cv, log, px + d[0] * prev, gy + y, pz + d[1] * prev); // liaison pour que le tronc reste d'un seul tenant
            setLog(cv, log, tx, gy + y, tz);
            prev = off;
        }
        int ty = gy + h;
        setLeaf(cv, leaf, tx, ty + 1, tz);
        int[][] eight = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] e : eight) {
            for (int k = 1; k <= 4; k++) {
                int yo = k <= 2 ? 1 : (k == 3 ? 0 : -1);
                int x = tx + e[0] * k, z = tz + e[1] * k;
                setLeaf(cv, leaf, x, ty + yo, z);
                if (e[0] != 0 && e[1] != 0) setLeaf(cv, leaf, x - e[0], ty + yo, z); // palme diagonale reliée
                if (e[0] == 0 || e[1] == 0) { // les palmes cardinales sont plus larges
                    if (k == 2 || k == 3) {
                        setLeaf(cv, leaf, x + e[1], ty + yo, z + e[0]);
                        setLeaf(cv, leaf, x - e[1], ty + yo, z - e[0]);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ campement

    private static BlockFace face(int fx, int fz) {
        if (fx == 1) return BlockFace.EAST;
        if (fx == -1) return BlockFace.WEST;
        return fz == 1 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Pose un bloc aux coordonnées locales du camp : a = vers l'oasis (porte), b = côté. */
    private static void put(Canvas cv, int ccx, int ccz, int fx, int fz, int a, int b, int y, BlockData d) {
        int x = ccx + fx * a + (-fz) * b;
        int z = ccz + fz * a + fx * b;
        cv.set(x, y, z, d);
    }

    private static void camp(Canvas cv, Random r, String world, int cx, int cz, double ringR, int y0, ChestSink sink) {
        int ccx = 0, ccz = 0, yc = NONE, fx = 0, fz = 0;
        boolean found = false;
        int[] tmp = new int[1];
        for (int attempt = 0; attempt < 10 && !found; attempt++) {
            double a = r.nextDouble() * Math.PI * 2;
            double dist = ringR + 5.5;
            ccx = cx + (int) Math.round(Math.cos(a) * dist);
            ccz = cz + (int) Math.round(Math.sin(a) * dist);
            boolean ok = true;
            for (int dx = -4; dx <= 4 && ok; dx++) {
                for (int dz = -4; dz <= 4 && ok; dz++) ok = flatNatural(cv, ccx + dx, ccz + dz, y0, tmp);
            }
            if (!ok) continue;
            yc = surface(cv, ccx, ccz);
            if (yc == NONE) continue;
            double vx = cx - ccx, vz = cz - ccz; // la porte regarde vers l'oasis
            if (Math.abs(vx) >= Math.abs(vz)) {
                fx = vx >= 0 ? 1 : -1;
                fz = 0;
            } else {
                fx = 0;
                fz = vz >= 0 ? 1 : -1;
            }
            // sol strictement plat sous la tente (5x5) et devant l'entrée
            int sxp = -fz, szp = fx;
            for (int aa = -2; aa <= 2 && ok; aa++) {
                for (int bb = -2; bb <= 2 && ok; bb++) {
                    ok = surface(cv, ccx + fx * aa + sxp * bb, ccz + fz * aa + szp * bb) == yc;
                }
            }
            for (int k = 3; k <= 4 && ok; k++) ok = surface(cv, ccx + fx * k, ccz + fz * k) == yc;
            found = ok;
        }
        if (!found) return;
        BlockData air = Material.AIR.createBlockData();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int k = 1; k <= 6; k++) {
                    Material m = cv.type(ccx + dx, yc + k, ccz + dz);
                    if (m != null && !m.isAir() && (plant(m) || m == Material.CACTUS)) cv.set(ccx + dx, yc + k, ccz + dz, air);
                }
            }
        }

        // ---- tente en A, 5x5, rayée
        String[] colors = {"RED", "ORANGE", "YELLOW", "LIGHT_BLUE", "BROWN", "GREEN", "PURPLE"};
        String col = colors[r.nextInt(colors.length)];
        Material wallM = Material.getMaterial(col + "_WOOL");
        Material whiteM = Material.getMaterial("WHITE_WOOL");
        if (wallM == null) wallM = Material.SANDSTONE;
        if (whiteM == null) whiteM = Material.SANDSTONE;
        BlockData wallD = wallM.createBlockData();
        BlockData whiteD = whiteM.createBlockData();
        Material carpetM = Material.getMaterial(col + "_CARPET");
        BlockData carpetD = carpetM == null ? null : carpetM.createBlockData();
        for (int a = -2; a <= 2; a++) {
            BlockData row = (a & 1) == 0 ? wallD : whiteD; // une bande sur deux
            for (int b = -2; b <= 2; b++) {
                int ab = Math.abs(b);
                if (ab == 2) {
                    put(cv, ccx, ccz, fx, fz, a, b, yc + 1, row);
                    put(cv, ccx, ccz, fx, fz, a, b, yc + 2, row);
                }
                if (a == -2) { // fond de la tente
                    if (ab <= 1) {
                        put(cv, ccx, ccz, fx, fz, a, b, yc + 1, row);
                        put(cv, ccx, ccz, fx, fz, a, b, yc + 2, row);
                    }
                    if (ab == 0) put(cv, ccx, ccz, fx, fz, a, b, yc + 3, row);
                }
                if (ab == 1) put(cv, ccx, ccz, fx, fz, a, b, yc + 3, row);
                if (ab == 0) put(cv, ccx, ccz, fx, fz, a, b, yc + 4, row);
                if (ab <= 1 && a >= -1 && carpetD != null) put(cv, ccx, ccz, fx, fz, a, b, yc + 1, carpetD);
            }
        }
        // sol sous la tente en sable de grès pour ne pas laisser d'herbe
        // ---- intérieur : lit, coffre, tonneau, établi, lanterne suspendue
        BlockData bedD = Material.RED_BED.createBlockData();
        if (bedD instanceof Bed bed) {
            BlockFace toBack = face(-fx, -fz);
            bed.setFacing(toBack);
            bed.setPart(Bed.Part.FOOT);
            put(cv, ccx, ccz, fx, fz, 0, -1, yc + 1, bed);
            BlockData head = bed.clone();
            ((Bed) head).setPart(Bed.Part.HEAD);
            put(cv, ccx, ccz, fx, fz, -1, -1, yc + 1, head);
        }
        BlockData chestD = Material.CHEST.createBlockData();
        if (chestD instanceof Chest ch) ch.setFacing(face(fx, fz));
        put(cv, ccx, ccz, fx, fz, -1, 1, yc + 1, chestD);
        sink.add(world, ccx - fx + (-fz) * 1, yc + 1, ccz - fz + fx * 1);
        put(cv, ccx, ccz, fx, fz, 0, 1, yc + 1, Material.BARREL.createBlockData());
        put(cv, ccx, ccz, fx, fz, 1, 1, yc + 1, Material.CRAFTING_TABLE.createBlockData());
        BlockData lantern = Material.LANTERN.createBlockData();
        if (lantern instanceof Lantern l) l.setHanging(true);
        put(cv, ccx, ccz, fx, fz, 0, 0, yc + 3, lantern);

        // ---- devant la tente : feu de camp, souches pour s'asseoir, bottes de paille, tonneau, torches sur poteaux
        put(cv, ccx, ccz, fx, fz, 4, 0, yc + 1, Material.CAMPFIRE.createBlockData());
        BlockData stump = Material.OAK_LOG.createBlockData();
        put(cv, ccx, ccz, fx, fz, 4, 2, yc + 1, stump);
        put(cv, ccx, ccz, fx, fz, 4, -2, yc + 1, stump);
        put(cv, ccx, ccz, fx, fz, 3, 3, yc + 1, Material.HAY_BLOCK.createBlockData());
        put(cv, ccx, ccz, fx, fz, 3, -3, yc + 1, Material.BARREL.createBlockData());
        BlockData fence = Material.OAK_FENCE.createBlockData();
        BlockData post = Material.LANTERN.createBlockData();
        for (int sb : new int[] {-2, 2}) {
            put(cv, ccx, ccz, fx, fz, 3, sb, yc + 1, fence);
            put(cv, ccx, ccz, fx, fz, 3, sb, yc + 2, fence);
            put(cv, ccx, ccz, fx, fz, 3, sb, yc + 3, post);
        }
    }
}
