package com.manhwa.manhwa_translator.service;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;
import org.springframework.stereotype.Service;

@Service
public class ImageInpaintingService {

    public Mat cleanBubble(Mat roi) {
        Mat gray = new Mat();
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);

        double meanBrightness = Core.mean(gray).val[0];
        boolean isDark = meanBrightness < 100;

        Mat textMask = isDark ? buildDarkBgMask(gray) : buildLightBgMask(gray);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.dilate(textMask, textMask, kernel);
        Imgproc.morphologyEx(textMask, textMask, Imgproc.MORPH_CLOSE, kernel);
        kernel.release();

        int rows = gray.rows(), cols = gray.cols();
        gray.release();

        int borderGuard = 2;
        textMask.submat(0, borderGuard, 0, cols).setTo(new Scalar(0));
        textMask.submat(rows - borderGuard, rows, 0, cols).setTo(new Scalar(0));
        textMask.submat(0, rows, 0, borderGuard).setTo(new Scalar(0));
        textMask.submat(0, rows, cols - borderGuard, cols).setTo(new Scalar(0));

        Mat filteredMask = filterBlobs(textMask, rows, cols);
        textMask.release();

        Mat result = roi.clone();

        if (isDark) {
            result.setTo(new Scalar(0, 0, 0), filteredMask);
            filteredMask.release();
            return result;
        }

        Mat notMask = new Mat();
        Core.bitwise_not(filteredMask, notMask);
        Mat interior = Mat.zeros(new Size(cols, rows), CvType.CV_8U);
        int s = 8;
        interior.submat(s, rows - s, s, cols - s).setTo(new Scalar(255));
        Mat sampleMask = new Mat();
        Core.bitwise_and(interior, notMask, sampleMask);
        Scalar bgColor = Core.mean(roi, sampleMask);
        result.setTo(bgColor, filteredMask);
        notMask.release();
        interior.release();
        sampleMask.release();

        Mat inpainted = new Mat();
        Photo.inpaint(result, filteredMask, inpainted, 10, Photo.INPAINT_TELEA);
        result.release();
        filteredMask.release();

        return inpainted;
    }

    private Mat buildDarkBgMask(Mat gray) {
        Mat mask = new Mat();
        Imgproc.threshold(gray, mask, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, 30, 120);
        Core.bitwise_or(mask, edges, mask);
        edges.release();
        return mask;
    }

    private Mat buildLightBgMask(Mat gray) {
        Mat inv = new Mat();
        Core.bitwise_not(gray, inv);
        Mat hard = new Mat();
        Imgproc.threshold(inv, hard, 60, 255, Imgproc.THRESH_BINARY);
        inv.release();

        Mat adapt = new Mat();
        Imgproc.adaptiveThreshold(gray, adapt, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, 21, 5);

        Mat km = buildKmeansMask(gray);

        Mat mask = new Mat();
        Core.bitwise_or(hard, adapt, mask);
        Core.bitwise_or(mask, km, mask);
        hard.release(); adapt.release(); km.release();

        Mat white = new Mat();
        Imgproc.threshold(gray, white, 220, 255, Imgproc.THRESH_BINARY);
        Mat notWhite = new Mat();
        Core.bitwise_not(white, notWhite);
        Core.bitwise_and(mask, notWhite, mask);
        white.release(); notWhite.release();

        return mask;
    }

    private Mat buildKmeansMask(Mat gray) {
        Mat samples = new Mat();
        gray.reshape(1, gray.rows() * gray.cols()).convertTo(samples, CvType.CV_32F);
        Mat labels = new Mat(), centers = new Mat();
        TermCriteria tc = new TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 100, 0.2);
        Core.kmeans(samples, 3, labels, tc, 3, Core.KMEANS_PP_CENTERS, centers);

        int total = (int) labels.total();
        int[] lb = new int[total];
        labels.get(0, 0, lb);
        int[] counts = new int[3];
        for (int v : lb) counts[v]++;

        int textLabel = 0;
        double bestScore = -1;
        for (int i = 0; i < 3; i++) {
            double score = Math.abs(centers.get(i, 0)[0] - 128.0) / (Math.sqrt(counts[i]) + 1.0);
            if (score > bestScore) { bestScore = score; textLabel = i; }
        }

        Mat mask = new Mat(gray.size(), CvType.CV_8U, new Scalar(0));
        for (int row = 0; row < gray.rows(); row++)
            for (int col = 0; col < gray.cols(); col++)
                if (lb[row * gray.cols() + col] == textLabel)
                    mask.put(row, col, 255);

        samples.release(); labels.release(); centers.release();
        return mask;
    }

    private Mat filterBlobs(Mat rawMask, int rows, int cols) {
        Mat blobLabels = new Mat(), stats = new Mat(), centroids = new Mat();
        int numLabels = Imgproc.connectedComponentsWithStats(
                rawMask, blobLabels, stats, centroids);

        Mat filteredMask = Mat.zeros(rawMask.size(), rawMask.type());
        Mat componentMask = new Mat();
        int maxArea = (int) (rows * cols * 0.30);

        for (int i = 1; i < numLabels; i++) {
            int area = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
            int w    = (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
            int h    = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];

            boolean isOutline = (w > cols * 0.80) && (h > rows * 0.80);

            if (area >= 8 && area <= maxArea && !isOutline) {
                Core.compare(blobLabels, new Scalar(i), componentMask, Core.CMP_EQ);
                Core.bitwise_or(filteredMask, componentMask, filteredMask);
            }
        }
        componentMask.release();
        blobLabels.release(); stats.release(); centroids.release();
        return filteredMask;
    }
}
