import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * The installer's pictures: the big one on the first and last page (graphite on the left, where WiX
 * leaves room for it) and the strip at the top of the other pages. Run from the repo root:
 * `java installer/Art.java build/installer`.
 */
public class Art {
    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        out.mkdirs();
        BufferedImage logo = ImageIO.read(new File("desktop/src/main/resources/oko.png"));
        Font bold = Font.createFont(Font.TRUETYPE_FONT, new File("C:/Windows/Fonts/segoeuib.ttf"));

        // 493×312: the left 164 px are the picture, the rest stays white under the page's text
        BufferedImage dlg = new BufferedImage(493, 312, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(dlg);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 493, 312);
        g.setPaint(new GradientPaint(0, 0, new Color(0x3A, 0x3B, 0x40), 0, 312, new Color(0x1E, 0x1F, 0x23)));
        g.fill(new Rectangle2D.Float(0, 0, 164, 312));
        // мягкое тёплое свечение за значком
        g.setPaint(new RadialGradientPaint(new Point2D.Float(82, 128), 110,
            new float[] {0f, 1f}, new Color[] {new Color(0xEE, 0xEA, 0xE3, 46), new Color(0xEE, 0xEA, 0xE3, 0)}));
        g.fill(new Rectangle2D.Float(0, 0, 164, 312));
        g.drawImage(logo, 82 - 48, 128 - 48, 96, 96, null);
        g.setFont(bold.deriveFont(26f));
        g.setColor(new Color(0xF2, 0xEF, 0xEA));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("Oko", 82 - fm.stringWidth("Oko") / 2f, 214);
        g.dispose();
        ImageIO.write(dlg, "bmp", new File(out, "dialog.bmp"));

        // 493×58: white strip, the logo on the right (the page title is drawn on the left)
        BufferedImage ban = new BufferedImage(493, 58, BufferedImage.TYPE_INT_RGB);
        g = start(ban);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 493, 58);
        g.drawImage(logo, 493 - 52, 7, 44, 44, null);
        g.dispose();
        ImageIO.write(ban, "bmp", new File(out, "banner.bmp"));
    }

    static Graphics2D start(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }
}
