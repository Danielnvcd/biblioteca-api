package com.biblioteca.service;

import com.biblioteca.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La categoría "perfiles" se sirve SIN autenticación (PUBLIC_CATEGORIES en
 * FileController) y cualquier usuario logueado puede escribir en ella vía
 * POST /api/auth/profile. Con solo la allow-list global eso permitía publicar
 * un PDF o un MP4 de 50 MB en una URL pública del dominio de la empresa.
 * Estos tests fijan que ahí solo entran imágenes, y que el resto de las
 * categorías conservan su allow-list amplia.
 */
class FileStorageServiceCategoryRulesTest {

    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    private FileStorageService service;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        service = new FileStorageService();
        ReflectionTestUtils.setField(service, "uploadDir", tmp.toString());
        ReflectionTestUtils.setField(service, "allowedExtensionsCsv",
                "pdf,doc,docx,ppt,pptx,xls,xlsx,xlsm,jpg,png,mp4,mp3,wav,heic");
        service.init();
    }

    @Test
    void perfilesRejectsDocumentsEvenThoughTheGlobalListAllowsThem() {
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "curriculum.pdf", "application/pdf", PDF_MAGIC);

        assertThatThrownBy(() -> service.store(pdf, "perfiles"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no permitida en esta sección");
    }

    @Test
    void perfilesAcceptsImages() {
        MockMultipartFile png = new MockMultipartFile(
                "file", "avatar.png", "image/png", PNG_MAGIC);

        String stored = service.store(png, "perfiles");

        assertThat(stored).endsWith("avatar.png");
    }

    @Test
    void otherCategoriesKeepTheWideAllowList() {
        // seguridad aloja manuales y podcasts de forma legítima — la restricción
        // por categoría no debe alcanzarla.
        MockMultipartFile pdf = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", PDF_MAGIC);

        String stored = service.store(pdf, "seguridad");

        assertThat(stored).endsWith("manual.pdf");
    }

    @Test
    void extensionRenamedToDodgeTheRuleStillFailsOnMagicBytes() {
        // Un PDF renombrado a .png pasa el filtro de categoría pero no el de
        // contenido — las dos defensas siguen siendo independientes.
        MockMultipartFile disfrazado = new MockMultipartFile(
                "file", "avatar.png", "image/png", PDF_MAGIC);

        assertThatThrownBy(() -> service.store(disfrazado, "perfiles"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no coincide con la extensión");
    }
}
