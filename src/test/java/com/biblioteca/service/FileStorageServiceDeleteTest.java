package com.biblioteca.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El borrado tiene que llevarse también lo que se generó a partir del archivo.
 *
 * El thumbnail no lo crea la subida: aparece la primera vez que alguien pide la
 * imagen. Por eso, al borrar, nadie lo tiene presente — y así se acumulaba
 * basura en silencio en todas las categorías (contenidos, quejas y fotos de
 * perfil), una copia por cada archivo que alguna vez se mostró.
 */
class FileStorageServiceDeleteTest {

    @TempDir
    Path uploads;

    private FileStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new FileStorageService();
        ReflectionTestUtils.setField(service, "uploadDir", uploads.toString());
        ReflectionTestUtils.setField(service, "allowedExtensionsCsv", "jpg,png,pdf");
        service.init();
        Files.createDirectories(uploads.resolve("perfiles"));
    }

    private Path crear(String nombre) throws Exception {
        Path p = uploads.resolve("perfiles").resolve(nombre);
        Files.writeString(p, "x");
        return p;
    }

    @Test
    void borraElOriginalYSusDerivados() throws Exception {
        Path original = crear("foto.jpg");
        Path thumb = crear("foto.jpg.thumb.jpg");
        Path tmp = crear("foto.jpg.thumb.jpg.tmp"); // generación interrumpida

        service.delete("foto.jpg", "perfiles");

        assertThat(original).doesNotExist();
        assertThat(thumb).doesNotExist();
        assertThat(tmp).doesNotExist();
    }

    @Test
    void noTocaLosArchivosDeOtros() throws Exception {
        Path mio = crear("foto.jpg");
        Path ajeno = crear("otra.jpg");
        Path thumbAjeno = crear("otra.jpg.thumb.jpg");

        service.delete("foto.jpg", "perfiles");

        assertThat(mio).doesNotExist();
        assertThat(ajeno).exists();
        assertThat(thumbAjeno).exists();
    }

    @Test
    void borrarLoQueNoExisteNoLanza() {
        // Se llama después de operaciones que ya modificaron la base: fallar
        // acá no debe deshacer lo anterior ni propagar un error al usuario.
        service.delete("no-existe.jpg", "perfiles");
        service.delete(null, "perfiles");
        service.delete("", "perfiles");
    }
}
