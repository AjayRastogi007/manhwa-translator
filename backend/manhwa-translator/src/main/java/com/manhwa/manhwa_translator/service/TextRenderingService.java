package com.manhwa.manhwa_translator.service;

import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

@Service
public class TextRenderingService {

    private static final int PADDING = 20;
    private static final int MAX_FONT = 48;
    private static final int MIN_FONT = 14;

    public BufferedImage drawTextLocal(BufferedImage image, String text) {

        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        int boxWidth = image.getWidth() - PADDING * 2;
        int boxHeight = image.getHeight() - PADDING * 2;

        Font bestFont = null;
        List<String> bestLines = null;

        for (int size = MAX_FONT; size >= MIN_FONT; size--) {

            Font font = new Font("Noto Sans", Font.BOLD, size);
            g.setFont(font);

            FontMetrics fm = g.getFontMetrics();

            List<String> lines = wrapText(text, fm, boxWidth);

            int totalHeight = lines.size() * fm.getHeight();

            if (totalHeight <= boxHeight) {
                bestFont = font;
                bestLines = lines;
                break;
            }
        }

        if (bestFont == null) {
            bestFont = new Font("Arial", Font.BOLD, MIN_FONT);
            g.setFont(bestFont);
            bestLines = wrapText(text, g.getFontMetrics(), boxWidth);
        }

        g.setFont(bestFont);
        FontMetrics fm = g.getFontMetrics();

        int lineHeight = (int) (fm.getHeight() * 1.2);
        int totalHeight = bestLines.size() * lineHeight;

        int y = PADDING + (boxHeight - totalHeight) / 2 + fm.getAscent();

        g.setColor(Color.BLACK);

        for (String line : bestLines) {

            int lineWidth = fm.stringWidth(line);
            int x = PADDING + (boxWidth - lineWidth) / 2;

            TextLayout textLayout = new TextLayout(line, g.getFont(), g.getFontRenderContext());
            Shape shape = textLayout.getOutline(AffineTransform.getTranslateInstance(x, y));

            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(4)); // thickness
            g.draw(shape);

            g.setColor(Color.BLACK);
            g.fill(shape);
            y += lineHeight;
        }

        g.dispose();
        return image;
    }

    private List<String> wrapText(String text, FontMetrics fm, int maxWidth) {

        List<String> lines = new ArrayList<>();

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();

        for (String word : words) {

            String test = line + word + " ";
            int width = fm.stringWidth(test);

            if (width > maxWidth && !line.isEmpty()) {
                lines.add(line.toString().trim());
                line = new StringBuilder(word + " ");
            } else {
                line.append(word).append(" ");
            }
        }

        if (!line.isEmpty()) {
            lines.add(line.toString().trim());
        }

        return lines;
    }
}
