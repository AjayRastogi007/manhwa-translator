package com.manhwa.manhwa_translator.service;

import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class TextDetectionService {

    private final Net net;

    public TextDetectionService() {

        try {
            ClassPathResource resource = new ClassPathResource("models/frozen_east_text_detection.pb");

            File tempFile = File.createTempFile("east-model", ".pb");
            tempFile.deleteOnExit();

            try (InputStream is = resource.getInputStream(); FileOutputStream os = new FileOutputStream(tempFile)) {

                is.transferTo(os);
            }

            this.net = Dnn.readNetFromTensorflow(tempFile.getAbsolutePath());

        } catch (Exception e) {
            throw new RuntimeException("Failed to load EAST model", e);
        }
    }

    public synchronized List<Rect> detectBubbles(Mat mat) {
        int origW = mat.width();
        int origH = mat.height();

        int newW = (int) (Math.ceil(origW / 32.0) * 32);
        int newH = (int) (Math.ceil(origH / 32.0) * 32);

        Size size = new Size(newW, newH);

        Mat resized = new Mat();
        Imgproc.resize(mat, resized, size);

        Mat blob = Dnn.blobFromImage(resized, 1.0, size, new Scalar(123.68, 116.78, 103.94), true, false);
        net.setInput(blob);

        List<Mat> outputs = new ArrayList<>();
        List<String> outNames = Arrays.asList("feature_fusion/Conv_7/Sigmoid", "feature_fusion/concat_3");

        net.forward(outputs, outNames);
        Mat scores = outputs.get(0);
        Mat geometry = outputs.get(1);

        List<Rect> boxes = decode(scores, geometry);

        double ratioX = (double) origW / newW;
        double ratioY = (double) origH / newH;

        List<Rect> scaledBoxes = new ArrayList<>();

        for (Rect rect : boxes) {
            int x = (int) (rect.x * ratioX);
            int y = (int) (rect.y * ratioY);
            int w = (int) (rect.width * ratioX);
            int h = (int) (rect.height * ratioY);

            scaledBoxes.add(new Rect(x, y, w, h));
        }

        scaledBoxes.removeIf(r -> r.width < 10 || r.height < 10);

        List<Rect> originalBoxes = new ArrayList<>(scaledBoxes);
        List<Rect> bubbles = groupTextIntoBubbles(mat, scaledBoxes, originalBoxes);

        blob.release();
        resized.release();
        for (Mat m : outputs) {
            m.release();
        }

        return nonMaxSuppression(bubbles);
    }

    private List<Rect> decode(Mat scores, Mat geometry) {
        List<Rect> rects = new ArrayList<>();

        int height = scores.size(2);
        int width = scores.size(3);

        float[] scoreData = new float[height * width];
        scores.reshape(1, 1).get(0, 0, scoreData);

        float[] geomData = new float[5 * height * width];
        geometry.reshape(1, 5).get(0, 0, geomData);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                float score = scoreData[index];

                if (score < 0.6f) continue;

                int offsetX = x * 4;
                int offsetY = y * 4;

                float t = geomData[index];
                float r = geomData[(height * width) + index];
                float b = geomData[2 * (height * width) + index];
                float l = geomData[3 * (height * width) + index];

                int boxWidth = (int) (l + r);
                int boxHeight = (int) (t + b);

                int startX = (int) (offsetX - l);
                int startY = (int) (offsetY - t);

                rects.add(new Rect(startX, startY, boxWidth, boxHeight));
            }
        }
        return rects;
    }

    private List<Rect> nonMaxSuppression(List<Rect> boxes) {

        if (boxes.isEmpty()) return boxes;

        List<Rect> result = new ArrayList<>();
        boxes.sort((a, b) -> Double.compare(b.area(), a.area()));
        boolean[] removed = new boolean[boxes.size()];

        for (int i = 0; i < boxes.size(); i++) {
            if (removed[i]) continue;

            Rect boxA = boxes.get(i);
            result.add(boxA);

            for (int j = i + 1; j < boxes.size(); j++) {
                if (removed[j]) continue;

                Rect boxB = boxes.get(j);
                double iou = intersectionOverUnion(boxA, boxB);

                if (iou > 0.3) {
                    removed[j] = true;
                }
            }
        }

        return result;
    }

    private double intersectionOverUnion(Rect a, Rect b) {

        int xA = Math.max(a.x, b.x);
        int yA = Math.max(a.y, b.y);
        int xB = Math.min(a.x + a.width, b.x + b.width);
        int yB = Math.min(a.y + a.height, b.y + b.height);

        int interArea = Math.max(0, xB - xA) * Math.max(0, yB - yA);

        int boxAArea = a.width * a.height;
        int boxBArea = b.width * b.height;

        return (double) interArea / (boxAArea + boxBArea - interArea);
    }

    private List<Rect> groupTextIntoBubbles(Mat mat, List<Rect> boxes, List<Rect> originalBoxes) {

        Mat mask = Mat.zeros(mat.size(), CvType.CV_8UC1);

        for (Rect rect : boxes) {

            int pad = 25;
            int x = Math.max(rect.x - pad, 0);
            int y = Math.max(rect.y - pad, 0);

            int w = Math.min(rect.width + pad * 2, mat.width() - x);
            int h = Math.min(rect.height + pad * 2, mat.height() - y);

            Rect expanded = new Rect(x, y, w, h);
            Imgproc.rectangle(mask, expanded, new Scalar(255), -1);
        }

        int k = Math.max(mat.width(), mat.height()) / 25;
        k = Math.max(40, k);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(k, k));
        Imgproc.dilate(mask, mask, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<Rect> bubbleBoxes = new ArrayList<>();

        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);

            if (isSpeechBubble(mat, rect, originalBoxes)) {
                bubbleBoxes.add(rect);
            }
        }

        bubbleBoxes = mergeNearbyRects(bubbleBoxes);
        return bubbleBoxes;
    }

    private List<Rect> mergeNearbyRects(List<Rect> rects) {
        List<Rect> result = new ArrayList<>();

        boolean[] merged = new boolean[rects.size()];

        for (int i = 0; i < rects.size(); i++) {
            if (merged[i]) continue;

            Rect a = rects.get(i);

            for (int j = i + 1; j < rects.size(); j++) {
                if (merged[j]) continue;

                Rect b = rects.get(j);

                // If vertically close → merge
                int dy = Math.abs((a.y + a.height/2) - (b.y + b.height/2));
                int dx = Math.abs((a.x + a.width/2) - (b.x + b.width/2));

                if (dy < 100 && dx < 150) {
                    a = union(a, b);
                    merged[j] = true;
                }
            }

            result.add(a);
        }

        return result;
    }

    private Rect union(Rect a, Rect b) {
        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        int w = Math.max(a.x + a.width, b.x + b.width) - x;
        int h = Math.max(a.y + a.height, b.y + b.height) - y;
        return new Rect(x, y, w, h);
    }

    public Rect clampRect(Rect r, int maxW, int maxH) {
        int x = Math.max(0, r.x);
        int y = Math.max(0, r.y);
        int w = Math.min(r.width, maxW - x);
        int h = Math.min(r.height, maxH - y);
        return new Rect(x, y, Math.max(1, w), Math.max(1, h));
    }

    private boolean isSpeechBubble(Mat mat, Rect rect, List<Rect> originalBoxes) {

        Mat roi = new Mat(mat, rect);
        Mat gray = new Mat();

        if (roi.channels() == 3) {
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = roi;
        }

        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, 80, 160);
        double edgeDensity = (double) Core.countNonZero(edges) / (rect.width * rect.height);
        if (edgeDensity > 0.35) return false;

        double aspect = (double) rect.width / rect.height;
        if (aspect < 0.1 || aspect > 4.0) return false;

        int textCount = countTextBoxesInside(rect, originalBoxes);
        return textCount >= 1 || rect.area() > 15000;
    }

    private int countTextBoxesInside(Rect big, List<Rect> smallBoxes) {

        int count = 0;

        for (Rect r : smallBoxes) {
            int centerX = r.x + r.width / 2;
            int centerY = r.y + r.height / 2;

            boolean inside = centerX >= big.x && centerX <= big.x + big.width && centerY >= big.y && centerY <= big.y + big.height;

            if (inside) {
                count++;
            }
        }

        return count;
    }
}