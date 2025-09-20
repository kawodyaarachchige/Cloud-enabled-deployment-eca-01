package lk.ise.eca.media.controller;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.Storage.BlobListOption;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@CrossOrigin
@RequestMapping("/files")
public class FileController {

    private final Path storageDir;
    private final Storage storage;
    private final String bucketName;
    private final Environment environment;

    public FileController(Environment environment) throws IOException {
        this.environment = environment;
        String configured = System.getenv().getOrDefault("MEDIA_STORAGE_DIR", "./data/media");
        this.storageDir = Paths.get(configured).toAbsolutePath().normalize();
        Files.createDirectories(this.storageDir);

        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucketName = "eca-media-bucket";
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty file"));
        }
        String original = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        String id = UUID.randomUUID().toString();
        String storedName = id + "__" + original;

        if (Arrays.asList(environment.getActiveProfiles()).contains("gcp")) {
            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, storedName).build();
            storage.create(blobInfo, file.getInputStream());
            String url = String.format("https://storage.googleapis.com/%s/%s", bucketName, storedName);
            return ResponseEntity.ok(Map.of("id", id, "filename", original, "url", url));
        } else {
            Path target = storageDir.resolve(storedName);
            try {
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return ResponseEntity.internalServerError().body(Map.of("error", "failed to store file"));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", id);
            resp.put("filename", original);
            resp.put("url", ServletUriComponentsBuilder
                    .fromCurrentContextPath()
                    .path("/files/%s-%s".formatted(id, original)).build().toUriString());
            return ResponseEntity.ok(resp);
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        if (Arrays.asList(environment.getActiveProfiles()).contains("gcp")) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (Blob blob : storage.list(bucketName, BlobListOption.currentDirectory()).iterateAll()) {
                Map<String, Object> file = new LinkedHashMap<>();
                String name = blob.getName();
                int idx = name.indexOf("__");
                String id = idx > 0 ? name.substring(0, idx) : name;
                String original = idx > 0 ? name.substring(idx + 2) : name;
                file.put("id", id);
                file.put("filename", original);
                file.put("url", String.format("https://storage.googleapis.com/%s/%s", bucketName, name));
                files.add(file);
            }
            return files;
        } else {
            if (!Files.exists(storageDir)) return List.of();
            try (var stream = Files.list(storageDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(p -> {
                            String name = p.getFileName().toString();
                            int idx = name.indexOf("__");
                            String id = idx > 0 ? name.substring(0, idx) : name;
                            String original = idx > 0 ? name.substring(idx + 2) : name;
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", id);
                            m.put("filename", original);
                            m.put("url", ServletUriComponentsBuilder
                                    .fromCurrentContextPath()
                                    .path("/files/%s-%s".formatted(id, original)).build().toUriString());
                            return m;
                        })
                        .collect(Collectors.toList());
            }
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getOne(@PathVariable String id) throws IOException {
        if (Arrays.asList(environment.getActiveProfiles()).contains("gcp")) {
            Blob blob = storage.get(BlobId.of(bucketName, id));
            if (blob == null) return ResponseEntity.notFound().build();
            byte[] content = blob.getContent();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + blob.getName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new ByteArrayResource(content));
        } else {
            Optional<Path> found = findById(id);
            if (found.isEmpty()) return ResponseEntity.notFound().build();
            Path path = found.get();
            FileSystemResource resource = new FileSystemResource(path);
            String filename = path.getFileName().toString();
            int idx = filename.indexOf("__");
            String original = idx > 0 ? filename.substring(idx + 2) : filename;
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + original + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        if (Arrays.asList(environment.getActiveProfiles()).contains("gcp")) {
            boolean deleted = storage.delete(BlobId.of(bucketName, id));
            if (!deleted) return ResponseEntity.notFound().build();
            return ResponseEntity.noContent().build();
        } else {
            Optional<Path> found = findById(id);
            if (found.isEmpty()) return ResponseEntity.notFound().build();
            Files.deleteIfExists(found.get());
            return ResponseEntity.noContent().build();
        }
    }

    private Optional<Path> findById(String id) throws IOException {
        if (!Files.exists(storageDir)) return Optional.empty();
        try (var stream = Files.list(storageDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(id + "__"))
                    .findFirst();
        }
    }
}