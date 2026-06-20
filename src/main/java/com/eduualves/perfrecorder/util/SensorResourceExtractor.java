package com.eduualves.perfrecorder.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Garante que {@code CpuTempSensor.exe} (empacotado como recurso dentro do .jar do mod,
 * em {@code /sensors/CpuTempSensor.exe}) exista na pasta de configuração antes que
 * {@link LibreHardwareMonitorBridge} tente executá-lo.
 * <p>
 * A extração só ocorre se o arquivo ainda não existir, ou se o tamanho em disco for
 * diferente do recurso embutido (cobre o caso de atualização do mod com uma versão
 * mais nova do sensor).
 */
public final class SensorResourceExtractor {

    private static final String RESOURCE_PATH = "/sensors/CpuTempSensor.exe";
    private static final Path TARGET_DIR = Path.of("config", "perfrecorder", "sensors");
    private static final Path TARGET_FILE = TARGET_DIR.resolve("CpuTempSensor.exe");

    private SensorResourceExtractor() {
    }

    /**
     * Extrai o executável se necessário. Não lança exceção: qualquer falha é logada
     * e o mod segue sem o sensor (cai no fallback do OSHI mais adiante).
     */
    public static void ensureExtracted() {
        try (InputStream resource = SensorResourceExtractor.class.getResourceAsStream(RESOURCE_PATH)) {
            if (resource == null) {
                // Build sem o sensor embutido (ex.: ambiente de desenvolvimento sem o
                // .exe compilado ainda). Não é um erro - apenas significa que a
                // temperatura ficará indisponível até o sensor ser publicado no jar.
                return;
            }

            Files.createDirectories(TARGET_DIR);

            if (Files.isRegularFile(TARGET_FILE) && Files.size(TARGET_FILE) == resourceSize()) {
                return; // já extraído e do mesmo tamanho; assume-se igual
            }

            Files.copy(resource, TARGET_FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[PerfRecorder] Não foi possível extrair CpuTempSensor.exe ("
                    + e.getMessage() + "); temperatura ficará indisponível.");
        }
    }

    private static long resourceSize() throws IOException {
        try (InputStream resource = SensorResourceExtractor.class.getResourceAsStream(RESOURCE_PATH)) {
            if (resource == null) return -1;
            return resource.readAllBytes().length;
        }
    }
}
