package com.manhwa.manhwa_translator.controller;

import com.manhwa.manhwa_translator.service.ManhwaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ManwhaController {

    private final ManhwaService manhwaService;

    @GetMapping("/translate-url")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/translate-url")
    public ResponseEntity<byte[]> translateUrl(@RequestBody Map<String, String> body) throws Exception {

        String url = body.get("url");
        URL imageUrl = new URL(url);

        HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();

        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Referer", "https://newtoki469.com/");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        InputStream inputStream = conn.getInputStream();

        BufferedImage image = ImageIO.read(inputStream);

        if (image == null) {
            throw new RuntimeException("Invalid image received from source");
        }

        BufferedImage result = manhwaService.processAndTranslate(image);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(result, "png", baos);

        return ResponseEntity.ok().body(baos.toByteArray());
    }
}
