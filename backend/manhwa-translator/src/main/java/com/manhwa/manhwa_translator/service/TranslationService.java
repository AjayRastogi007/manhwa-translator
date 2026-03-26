package com.manhwa.manhwa_translator.service;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TranslationService {

    private final Map<String, String> cache = new LinkedHashMap<>(1000, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > 1000;
        }
    };
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public TranslationService() {
        this.restTemplate = new RestTemplate();

        for (var converter : restTemplate.getMessageConverters()) {
            if (converter instanceof StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }

    public String translate(String text, String source, String target) {
        if (text == null || text.isBlank()) return "";

        text = text.trim().replaceAll("\\s+", " ");
        String cacheKey = source + "|" + target + "|" + text;

        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        try {

            URI uri = UriComponentsBuilder
                    .fromUriString("https://translate.googleapis.com/translate_a/single")
                    .queryParam("client", "gtx")
                    .queryParam("sl", source)
                    .queryParam("tl", target)
                    .queryParam("dt", "t")
                    .queryParam("ie", "UTF-8")
                    .queryParam("oe", "UTF-8")
                    .queryParam("q", text)
                    .encode(StandardCharsets.UTF_8)
                    .build()
                    .toUri();

            ResponseEntity<byte[]> response =
                    restTemplate.getForEntity(uri, byte[].class);

            assert response.getBody() != null;
            String responseBody = new String(response.getBody(), StandardCharsets.UTF_8);
            String translated = parseGoogleResponse(responseBody);

            if (!translated.equals("[parse error]")) {
                cache.put(cacheKey, translated);
            }

            return translated;

        } catch (Exception e) {
            System.err.println("Translation Error: " + e.getMessage());
        }
        return "[translation error]";
    }

    private String parseGoogleResponse(String response) {
        try {
            JsonNode root = mapper.readTree(response);
            StringBuilder result = new StringBuilder();

            if (root.isArray() && root.has(0)) {
                for (JsonNode part : root.get(0)) {
                    if (part.isArray() && part.has(0)) {
                        result.append(part.get(0).asString());
                    }
                }
            }

            return result.toString();
        } catch (Exception e) {
            System.err.println("Parse Error: " + e.getMessage());
            return "[parse error]";
        }
    }
}
