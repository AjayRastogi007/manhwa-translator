package com.manhwa.manhwa_translator.service;

import com.manhwa.manhwa_translator.util.OpenCVUtils;
import com.manhwa.manhwa_translator.wrapper.OCRResult;
import lombok.RequiredArgsConstructor;
import net.sourceforge.tess4j.ITesseract;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
public class ManhwaService {

    private final TextDetectionService textDetectionService;
    private final TranslationService translationService;
    private final OCRService ocrService;
    private final ImageInpaintingService inpaintingService;
    private final TextRenderingService textRenderingService;
    private final ExecutorService ocrExecutor;

    public BufferedImage processAndTranslate(BufferedImage image) throws ExecutionException, InterruptedException {

        Mat mat = OpenCVUtils.bufferedImageToMat(image);
        List<Rect> bubbles = sortBubbles(textDetectionService.detectBubbles(mat));

        if (bubbles.isEmpty()) {
            return image;
        }

        System.out.println("Total bubbles detected before filter: " + bubbles.size());
        for (Rect rect : bubbles) {
            double aspect = (double) rect.width / rect.height;
            int area = rect.width * rect.height;
            System.out.println("  rect=" + rect + " area=" + area + " aspect=" + String.format("%.2f", aspect) + (area < 8000 ? " REJECTED:area" : "") + (aspect > 3.5 ? " REJECTED:aspect_high" : "") + (aspect < 0.1 ? " REJECTED:aspect_low" : ""));
        }

        Mat annotated = mat.clone();
        for (Rect rect : bubbles) {
            double aspect = (double) rect.width / rect.height;
            int area = rect.width * rect.height;
            if (area < 8000 || aspect > 3.5 || aspect < 0.1) continue;
            Imgproc.rectangle(annotated, rect, new Scalar(0, 255, 0), 2);
        }

        Semaphore semaphore = new Semaphore(4);
        List<Future<OCRResult>> futures = new ArrayList<>();

        for (int i = 0; i < bubbles.size(); i++) {
            int index = i;
            Rect rect = bubbles.get(i);

            futures.add(ocrExecutor.submit(() -> {
                try {
                    semaphore.acquire();
                    try {
                        String text = processBubble(mat, rect);
                        return new OCRResult(index, text);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new OCRResult(index, "");
                }
            }));
        }

        String[] orderedTexts = new String[bubbles.size()];

        for (Future<OCRResult> f : futures) {
            OCRResult res = f.get();
            orderedTexts[res.index] = res.text;
        }

        List<Integer> validIndexes = new ArrayList<>();
        List<String> texts = new ArrayList<>();

        for (int i = 0; i < orderedTexts.length; i++) {
            if (orderedTexts[i] != null && !orderedTexts[i].isEmpty()) {
                validIndexes.add(i);
                texts.add(orderedTexts[i]);
            }
        }

        if (texts.isEmpty()) return image;

        String delimiter = "<<<SEP>>>";
        String combined = String.join(delimiter, texts);
        String translatedCombined = translationService.translate(combined, "ko", "en");
        String[] translatedParts = translatedCombined.split("\\Q" + delimiter + "\\E");
        String[] finalResults = new String[bubbles.size()];

        for (int i = 0; i < validIndexes.size(); i++) {
            int originalIndex = validIndexes.get(i);

            if (i < translatedParts.length) {
                finalResults[originalIndex] = translatedParts[i];
            } else {
                finalResults[originalIndex] = texts.get(i);
            }
        }

        for (int i = 0; i < bubbles.size(); i++) {

            String translated = finalResults[i];
            if (translated == null || translated.isBlank()) continue;

            Rect rect = bubbles.get(i);
            Mat roi = new Mat(mat, rect);
            Mat cleaned = null;

            try {
                cleaned = inpaintingService.cleanBubble(roi);
                BufferedImage cleanedImg = OpenCVUtils.matToBufferedImage(cleaned);

                cleanedImg = textRenderingService.drawTextLocal(cleanedImg, translated);
                Mat finalMat = OpenCVUtils.bufferedImageToMat(cleanedImg);

                try {
                    int pad = 5;

                    Rect safeRect = new Rect(rect.x + pad, rect.y + pad, rect.width - pad * 2, rect.height - pad * 2);

                    Mat targetRoi = mat.submat(safeRect);
                    Mat sourceRoi = finalMat.submat(new Rect(pad, pad, safeRect.width, safeRect.height));

                    try {
                        sourceRoi.copyTo(targetRoi);
                    } finally {
                        sourceRoi.release();
                        targetRoi.release();
                    }

                } finally {
                    finalMat.release();
                }

            } finally {
                roi.release();
                if (cleaned != null) cleaned.release();
            }
        }

        return OpenCVUtils.matToBufferedImage(mat);
    }

    private String processBubble(Mat mat, Rect rect) {
        try {
            double aspect = (double) rect.width / rect.height;
            int area = rect.width * rect.height;

            if (area < 8000 || aspect > 3.5 || aspect < 0.1) return "";

            int inset = 15;
            Rect inner = new Rect(rect.x + inset, rect.y + inset, Math.max(1, rect.width - inset * 2), Math.max(1, rect.height - inset * 2));
            inner = textDetectionService.clampRect(inner, mat.width(), mat.height());

            Mat roi = new Mat(mat, inner);
            boolean isVertical = inner.height > inner.width * 1.5;

            try {
                BufferedImage subImage = ocrService.preprocessForOCR(roi);
                String text;
                ITesseract tesseract = ocrService.borrow(isVertical);
                try {
                    text = tesseract.doOCR(subImage);
                } finally {
                    ocrService.returnToPool(tesseract, isVertical);
                }

                text = text.replaceAll("[~…·]", "");
                text = text.replaceAll("[^\\uAC00-\\uD7A3\\s.,!?()]", "");
                text = text.replaceAll("\\s+", " ").trim();

                if (text.isEmpty()) return "";

                return text;
            } finally {
                roi.release();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private List<Rect> sortBubbles(List<Rect> bubbles) {
        bubbles.sort((a, b) -> {

            int avgHeight = (a.height + b.height) / 2;

            if (Math.abs(a.y - b.y) > avgHeight * 0.5) {
                return Integer.compare(a.y, b.y);
            }

            return Integer.compare(a.x, b.x);
        });

        return bubbles;
    }
}
