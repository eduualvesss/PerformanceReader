package com.eduualves.perfrecorder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Ponto de entrada do mod no lado do cliente.
 * <p>
 * O mod é client-side only: ele mede a experiência local do jogador
 * (FPS, hardware da máquina local, etc), por isso não precisa de
 * nenhum componente no lado do servidor. Funciona normalmente tanto
 * em mundos singleplayer quanto em servidores multiplayer.
 */
public class PerfRecorderClient implements ClientModInitializer {

    public static final String MOD_ID = "perfrecorder";

    private static SessionRecorder recorder;
    private static int tickAccumulator = 0;

    /** Amostragem a cada 20 ticks de cliente (~1 vez por segundo, já que o jogo roda a 20 TPS). */
    private static final int TICKS_PER_SAMPLE = 20;

    @Override
    public void onInitializeClient() {
        recorder = new SessionRecorder();

        // Disparado quando o jogador entra em um mundo (singleplayer: criação/carregamento do
        // mundo; multiplayer: ao conectar a um servidor).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                String worldName = resolveWorldName(client);
                recorder.startSession(worldName);
                tickAccumulator = 0;
                System.out.println("[PerfRecorder] Sessão de gravação iniciada para o mundo: " + worldName);
            } catch (Exception e) {
                System.err.println("[PerfRecorder] Falha ao iniciar sessão: " + e.getMessage());
            }
        });

        // Disparado quando o jogador sai do mundo/desconecta do servidor.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            try {
                if (recorder.isActive()) {
                    Path report = recorder.endSession();
                    if (report != null) {
                        System.out.println("[PerfRecorder] Relatório salvo em: " + report.toAbsolutePath());
                    } else {
                        System.err.println("[PerfRecorder] Sessão finalizada, mas o relatório não pôde ser gerado.");
                    }
                }
            } catch (Exception e) {
                System.err.println("[PerfRecorder] Falha ao finalizar sessão: " + e.getMessage());
            }
        });

        // A cada tick do cliente: usado tanto para medir o tempo entre ticks (TPS local)
        // quanto para decidir quando tirar a amostra completa (1x por segundo).
        ClientTickEvents.END_CLIENT_TICK.register((Minecraft client) -> {
            try {
                if (!recorder.isActive()) return;

                recorder.onClientTick();
                tickAccumulator++;

                if (tickAccumulator >= TICKS_PER_SAMPLE) {
                    tickAccumulator = 0;
                    recorder.sampleOnce(client);
                }
            } catch (Exception e) {
                System.err.println("[PerfRecorder] Falha no tick de gravação: " + e.getMessage());
            }
        });
    }

    /**
     * Resolve um nome legível para a sessão atual, usado para nomear a pasta do relatório.
     * Em singleplayer, tenta usar o nome do mundo salvo; em multiplayer, usa o endereço
     * do servidor. Esta é uma informação cosmética (nome de pasta), então qualquer falha
     * aqui não deve impedir a gravação de dados - por isso o bloco try/catch amplo.
     */
    private static String resolveWorldName(Minecraft client) {
        try {
            if (client.isLocalServer() && client.getSingleplayerServer() != null) {
                return client.getSingleplayerServer().getWorldData().getLevelName();
            }
        } catch (Exception ignored) {
            // Em caso de mudança de API entre versões, cai no fallback abaixo
            // em vez de impedir o início da gravação.
        }
        try {
            if (client.getCurrentServer() != null) {
                return client.getCurrentServer().name;
            }
        } catch (Exception ignored) {
            // idem
        }
        return "sessao_desconhecida";
    }
}
