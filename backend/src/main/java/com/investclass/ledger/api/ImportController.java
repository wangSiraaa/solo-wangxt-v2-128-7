package com.investclass.ledger.api;

import com.investclass.ledger.importing.ImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(path = "/api/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportService.Accepted upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceSystem", defaultValue = "teaching-upload")
            String sourceSystem) throws Exception {
        return imports.accept(file.getOriginalFilename(), file.getBytes(), sourceSystem);
    }
}
