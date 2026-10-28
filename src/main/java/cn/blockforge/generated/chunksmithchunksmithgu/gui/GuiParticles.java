package cn.blockforge.generated.chunksmithchunksmithgu.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板边缘的运行进度粒子：沿面板左右边缘缓慢上浮的小光点，
 * 任务运行时持续生成，面板完成时来一次金色爆闪。纯 2D fill，开销可忽略。
 */
public final class GuiParticles {

    private static final class P {
        double x, y, vx, vy;
        int color, life, maxLife, size;
    }

    private static final List<P> LIST = new ArrayList<>();
    private static long lastEdgeSpawn = 0;
    /** 共用一个随机源，别每次 spawn 都 new Random()。 */
    private static final java.util.Random RND = new java.util.Random();

    /** 每帧调用；running 决定是否沿边缘持续冒粒子。 */
    public static void tickRender(GuiGraphics g, int px, int py, int pw, int ph, boolean running, long now) {
        if (running && now - lastEdgeSpawn > 90 && LIST.size() < 160) {
            lastEdgeSpawn = now;
            spawnEdge(px, py, pw, ph);
        }
        for (int i = LIST.size() - 1; i >= 0; i--) {
            P p = LIST.get(i);
            p.x += p.vx;
            p.y += p.vy;
            p.life--;
            if (p.life <= 0) { LIST.remove(i); continue; }
            int a = (int) (255.0 * p.life / p.maxLife);
            int c = (Math.min(255, a) << 24) | (p.color & 0xFFFFFF);
            g.fill((int) p.x, (int) p.y, (int) p.x + p.size, (int) p.y + p.size, c);
        }
    }

    private static void spawnEdge(int px, int py, int pw, int ph) {
        java.util.Random r = RND;
        for (int i = 0; i < 2; i++) {
            P p = new P();
            boolean left = r.nextBoolean();
            p.x = px + (left ? -2 : pw + 1) + r.nextInt(2);
            p.y = py + 20 + r.nextInt(Math.max(1, ph - 30));
            p.vx = (r.nextDouble() - 0.5) * 0.15;
            p.vy = -(0.4 + r.nextDouble() * 0.7);
            p.color = 0x2FD9FF;
            p.maxLife = p.life = 40 + r.nextInt(40);
            p.size = 1 + r.nextInt(2);
            LIST.add(p);
        }
    }

    /** 完成爆闪：沿四边撒一圈金色粒子。 */
    public static void burst(int px, int py, int pw, int ph) {
        java.util.Random r = RND;
        for (int i = 0; i < 60; i++) {
            P p = new P();
            double side = r.nextInt(4);
            if (side == 0) { p.x = px + r.nextDouble() * pw; p.y = py; }
            else if (side == 1) { p.x = px + r.nextDouble() * pw; p.y = py + ph; }
            else if (side == 2) { p.x = px; p.y = py + r.nextDouble() * ph; }
            else { p.x = px + pw; p.y = py + r.nextDouble() * ph; }
            p.vx = (r.nextDouble() - 0.5) * 1.6;
            p.vy = (r.nextDouble() - 0.5) * 1.6;
            p.color = 0xFFD24A;
            p.maxLife = p.life = 30 + r.nextInt(40);
            p.size = 2 + r.nextInt(2);
            LIST.add(p);
        }
    }

    public static void clear() { LIST.clear(); }
}
