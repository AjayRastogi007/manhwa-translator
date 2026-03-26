package com.manhwa.manhwa_translator.service;

import com.manhwa.manhwa_translator.util.OpenCVUtils;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class OCRService {

    private final BlockingQueue<ITesseract> horizontalPool = new LinkedBlockingQueue<>();
    private final BlockingQueue<ITesseract> verticalPool = new LinkedBlockingQueue<>();
    private final String tessDataPath;

    public OCRService() {
        try {
            File tessDataDir = extractTessdata();
            this.tessDataPath = tessDataDir.getAbsolutePath();

            System.setProperty("TESSDATA_PREFIX", tessDataPath);

            for (int i = 0; i < 4; i++) {
                horizontalPool.add(buildInstance(false));
                verticalPool.add(buildInstance(true));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to init Tesseract", e);
        }
    }

    private ITesseract buildInstance(boolean isVertical) {
        Tesseract t = new Tesseract();
        t.setDatapath(tessDataPath);
        t.setLanguage("kor");
        t.setOcrEngineMode(1);
        t.setPageSegMode(isVertical ? 5 : 6);
        t.setVariable("classify_bln_numeric_mode", "0");
        t.setVariable("textord_heavy_nr", "1");
        t.setVariable("segment_penalty_dict_nonword", "1");
        t.setVariable("preserve_interword_spaces", "1");
        t.setVariable("user_defined_dpi", "300");
        t.setVariable("tessedit_char_blacklist", "0123456789");
        return t;
    }

    public ITesseract borrow(boolean isVertical) throws InterruptedException {
        return isVertical ? verticalPool.take() : horizontalPool.take();
    }

    public void returnToPool(ITesseract t, boolean isVertical) {
        if (isVertical) verticalPool.add(t);
        else horizontalPool.add(t);
    }

    private File extractTessdata() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "tessdata_" + System.currentTimeMillis());

        if (!tempDir.mkdirs()) {
            throw new IOException("Failed to create tessdata temp dir: " + tempDir.getAbsolutePath());
        }

        String[] langs = {"kor", "osd"};
        for (String lang : langs) {
            String resourcePath = "tessdata/" + lang + ".traineddata";
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) continue;
            File outFile = new File(tempDir, lang + ".traineddata");
            try (InputStream in = resource.getInputStream(); FileOutputStream out = new FileOutputStream(outFile)) {
                in.transferTo(out);
            }
        }
        return tempDir;
    }

    public BufferedImage preprocessForOCR(Mat roi) {
        Mat gray = new Mat();
        Mat thresh = new Mat();

        try {
            if (roi.channels() > 1) {
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = roi.clone();
            }

            Imgproc.resize(gray, gray, new Size(), 2.0, 2.0, Imgproc.INTER_CUBIC);

            if (isHighContrast(gray)) {
                return OpenCVUtils.matToBufferedImage(gray);
            }

            Imgproc.threshold(gray, thresh, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

            return OpenCVUtils.matToBufferedImage(thresh);

        } finally {
            gray.release();
            thresh.release();
        }
    }

    private boolean isHighContrast(Mat gray) {
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        Core.meanStdDev(gray, mean, std);
        return std.get(0, 0)[0] > 60;
    }
}